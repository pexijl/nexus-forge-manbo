package com.nexusforge.bootstrap;

import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.service.ModelCatalogService;
import com.nexusforge.ai.tools.EchoTool;
import com.nexusforge.config.AiProperties;
import com.nexusforge.client.LlmClient;
import com.nexusforge.router.ChatModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * nexus-forge-ai 模块自动装配入口。
 *
 * <p>spring-ai-full-migration Phase 2a:ChatModel 改为 Spring AI 的
 * {@link org.springframework.ai.chat.model.ChatModel}(本类 import)。
 * vendor 名由各 provider starter 的 ChatModel bean 自带,这里按
 * {@link ChatModel#name()} 索引成 {@code Map<String, ChatModel>} 供 router 查。
 *
 * <p>其他 bean 收敛同前:路由器 / 私 Key 加解密 / 启动日志。
 *(Phase 4 删除共享 HTTP / 熔断 bean;熔断留到 Phase 5 重做。)
 */
@AutoConfiguration
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@EnableConfigurationProperties(AiProperties.class)
@ConditionalOnClass({ChatModel.class, ChatModelRouter.class})
@ConditionalOnProperty(name = "spring.ai.enabled", havingValue = "true", matchIfMissing = true)
public class AiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiAutoConfiguration.class);

    /**
     * 路由 ChatModelRouter:按 Spring bean 名索引注入所有 Spring AI ChatModel 实现。
     *
     * <p>Phase 5 起改用 Spring 原生 {@code Map<String, ChatModel>} 注入 —
     * Spring 容器按 bean 名(各 vendor starter 用的 {@code <vendor>ChatModel} 命名)
     * 自动收集所有 ChatModel bean,不需要本类手动 iterate 反射。
     * bean 名 → 内部 vendor 名的归一化(剥 {@code ChatModel} 后缀 + 转小写)
     * 在 {@link ChatModelRouter} ctor 内部完成。
     *
     * <p>Phase 4 简化:不再注入 {@code ChatModelHttpSupport} 熔断器(已删除,
     * 熔断留到未来用 Spring AI retry / Resilience4j 重做)。
     */
    @Bean
    @ConditionalOnMissingBean(ChatModelRouter.class)
    public ChatModelRouter chatModelRouter(Map<String, ChatModel> models,
                                           AiProperties props,
                                           com.nexusforge.ai.service.FallbackChainService fallbackChainService) {
        return new ChatModelRouter(models, props, fallbackChainService);
    }

    /**
     * LlmClient:Spring 容器 bean 入口(Phase 2a 简化为 2 参 ctor,
     * Phase 3 接入 ToolCallingManager 时扩为 3 参,加 {@link ToolCallbackProvider}
     * 列表用于把 @Tool 方法注册成 tool callbacks)。
     *
     * <p>Phase 1 — 增 {@link ModelCatalogService} 注入,LlmClient 在
     * 每次调用前查 catalog 校验(admin 一键关停即时生效)。
     */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient llmClient(ChatModelRouter router,
                               AiProperties props,
                               List<ToolCallbackProvider> toolCallbackProviders,
                               ModelCatalogService modelCatalogService,
                               com.nexusforge.ai.provider.SystemKeyChatModelFactory systemKeyFactory) {
        return new LlmClient(router, props, toolCallbackProviders, modelCatalogService, systemKeyFactory);
    }

    /**
     * Phase 3 — 把所有含 {@code @Tool} 注解方法的 bean 扫成 {@code ToolCallback}
     * 列表。当前内置 {@link EchoTool} 一个,后续加业务工具只需在 @Component
     * 类里写 @Tool 方法即可自动被发现。
     *
     * <p>Spring AI 不会自动发现 @Tool 方法(不像 @Component 那样),必须显式
     * 注册 {@link MethodToolCallbackProvider} bean 指向工具对象。
     */
    @Bean
    @ConditionalOnMissingBean(name = "toolCallbackProvider")
    public ToolCallbackProvider toolCallbackProvider(EchoTool echoTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(echoTool)
                .build();
    }

    /**
     * P7 — 用户私 Key 加解密工具。AES-256-GCM,主密钥派生自
     * {@code spring.ai.preference.master-key},缺省时降级用 {@code jwt.secret}。
     */
    @Bean
    @ConditionalOnMissingBean(ApiKeyCipher.class)
    public ApiKeyCipher apiKeyCipher(
            @Value("${spring.ai.preference.master-key:}") String masterKey,
            @Value("${jwt.secret:}") String jwtSecret) {
        return new ApiKeyCipher(masterKey, jwtSecret);
    }

    /**
     * 启动日志:打印当前实际激活的 ChatModel 清单 + 配置摘要。
     *
     * <p>Phase 5 改:vendor 名列表从 router 拿(router 已归一化 bean 名 →
     * 小写 vendor 名),不再自己 iterate 反射。日志里的"vendors"是路由视角
     * 的 vendor 集合,跟 router 实际能路由到的目标一致。
     */
    @Bean
    public CommandLineRunner aiStartupLogger(ChatModelRouter router,
                                             AiProperties props,
                                             com.nexusforge.ai.service.FallbackChainService fallbackChainService) {
        return args -> {
            List<String> vendorNames = new ArrayList<>(router.vendorNames());

            // Phase 7 — 启动日志反映 DB-优先降级链(跟 ChatModelRouter.resolveWithFallback
            // 数据源保持一致,admin 一眼看出当前生效降级链是 DB 还是 yaml)
            var fbView = fallbackChainService.findEffective();
            String fbTag = "[" + fbView.source() + "]";

            log.info("[AI startup] defaultVendor={} defaultModel={} vendors={} fallbackChain={} {}",
                    props.getDefaultVendor(), props.getDefaultModel(),
                    vendorNames, fbView.vendors(), fbTag);
        };
    }

    /**
     * Phase 1 — 模型目录 seed runner。
     *
     * <p>首次启动时(catalog 表完全空)把 yaml {@code spring.ai.providers.<v>.default-model}
     * 拷到 {@code ai_model_catalog} 表,作为"已存在可用 model"的初始集合。
     * 之后 catalog 有数据就不再 seed(避免"yaml 改 default 但 DB 已经有别的
     * default"被覆盖的脏场景)。
     *
     * <p>执行顺序:ApplicationContext 装配完成 → EnvironmentPostProcessor 跑过
     * (bridge 写完属性)→ 本 runner 跑(seed DB)→ HTTP server accept。命令执行
     * 时点位于所有 bean wire 完毕、HTTP server 启动之前,DB 已就绪。
     *
     * <p>为什么不放在 {@code ProviderPropertiesBridge} 里:bridge 是
     * EnvironmentPostProcessor,运行极早,JPA 还没装配;runner 走 JPA,
     * 时序正确。
     */
    @Bean
    public CommandLineRunner aiModelCatalogSeedRunner(
            com.nexusforge.ai.service.ModelCatalogService catalogService,
            AiProperties props) {
        return args -> {
            // 把 AiProperties.getProviders() 转成 (vendor -> defaultModel) 喂给 seed
            java.util.Map<String, String> yamlDefaults = new java.util.LinkedHashMap<>();
            if (props.getProviders() != null) {
                for (var entry : props.getProviders().entrySet()) {
                    String vendor = entry.getKey();
                    String defaultModel = entry.getValue() == null ? null : entry.getValue().getDefaultModel();
                    if (vendor != null && defaultModel != null && !defaultModel.isBlank()) {
                        yamlDefaults.put(vendor, defaultModel);
                    }
                }
            }
            int created = catalogService.seedFromYamlIfEmpty(yamlDefaults);
            log.info("[AI startup seed] model catalog seed 完成: 新增 {} 条 (yaml 中共 {} 个 default-model)",
                    created, yamlDefaults.size());
        };
    }

    /**
     * Phase 2 — vendor 配置 seed runner。
     *
     * <p>首次启动时(vendor config 表完全空)把 yaml {@code spring.ai.providers.<v>.{base-url, enabled}}
     * 拷到 {@code ai_vendor_config} 表。之后 DB 是 source of truth,admin 通过
     * {@code /api/admin/ai/vendors} 改。
     *
     * <p>跟 model catalog seed 的区别:catalog seed 需要 (vendor, default-model) 映射,
     * 本 runner 只需读 yaml {@code props.getProviders()} 即可(每个 entry 自带
     * baseUrl / enabled / defaultModel)。
     */
    @Bean
    public CommandLineRunner aiVendorConfigSeedRunner(
            com.nexusforge.ai.service.VendorConfigService vendorConfigService) {
        return args -> {
            int created = vendorConfigService.seedFromYamlIfEmpty();
            log.info("[AI startup seed] vendor config seed 完成: 新增 {} 条", created);
        };
    }
}