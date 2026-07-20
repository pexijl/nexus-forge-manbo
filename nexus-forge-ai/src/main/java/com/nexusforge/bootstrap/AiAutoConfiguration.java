package com.nexusforge.bootstrap;

import com.nexusforge.config.AiProperties;
import com.nexusforge.model.ChatModel;
import com.nexusforge.client.FunctionCallAggregator;
import com.nexusforge.client.LlmClient;
import com.nexusforge.provider.support.ChatModelHttpSupport;
import com.nexusforge.router.ChatModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * nexus-forge-ai 模块自动装配入口。
 *
 * <p>本类在 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 中声明,Spring Boot 启动时自动加载。{@code spring.ai.enabled=false} 可整体禁用本模块;
 * 单个 vendor 通过 {@code spring.ai.providers.<vendor>.enabled} 单独开关。
 *
 * <p>P4 起提供下列 bean(P4 Step 10 显式收敛):
 * <ul>
 *   <li>{@link ChatModelRouter} — vendor 名 → ChatModel 的索引,带降级链解析</li>
 *   <li>{@link ChatModelHttpSupport} — 共享 HttpClient 缓存 + 重试 + 熔断(显式 @Bean,
 *       文档化;此前依赖 {@code @Component} 扫描)</li>
 *   <li>{@code aiStartupLogger} — {@link CommandLineRunner} 在启动后打印当前注册的
 *       ChatModel 清单 + 配置摘要,方便运维与排障</li>
 * </ul>
 *
 * <p>其他工具组件({@link com.nexusforge.provider.openai.OpenAiJsonMapper} /
 * {@link com.nexusforge.stream.OpenAiStreamParser} /
 * {@link com.nexusforge.provider.anthropic.AnthropicMessagesStreamParser} /
 * 各 ChatModel 子类)通过自身的 {@code @Component} + {@code @ConditionalOnProperty} 自动注册,
 * 不在此列出。
 *
 * <p>注意:本类不可被 Spring 容器以 {@code @Component} 扫描注册(它本来就是 bean 工厂),
 * 仅由 {@code AutoConfiguration.imports} 加载。
 */
@AutoConfiguration
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@EnableConfigurationProperties(AiProperties.class)
@ConditionalOnClass({ChatModel.class, ChatModelRouter.class})
@ConditionalOnProperty(name = "spring.ai.enabled", havingValue = "true", matchIfMissing = true)
public class AiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiAutoConfiguration.class);

    /**
     * 路由 ChatModelRouter:按 vendor {@code name()} 索引注入所有 ChatModel 实现。
     *
     * <p>{@link ChatModelHttpSupport} 用 {@link ObjectProvider} 注入 —— 它本身是 P4 Step 2
     * 新增的组件,且 Step 8 降级链只读 {@link ChatModelHttpSupport#isVendorOpen(String)},
     * 缺失时 router 自动退化为"不查熔断",所以不强依赖。
     *
     * <p>{@link ConditionalOnMissingBean} 让用户在测试或定制场景下能用 {@code @Bean} 替换。
     */
    @Bean
    @ConditionalOnMissingBean(ChatModelRouter.class)
    public ChatModelRouter chatModelRouter(List<ChatModel> models,
                                           AiProperties props,
                                           ObjectProvider<ChatModelHttpSupport> httpProvider) {
        Map<String, ChatModel> map = new HashMap<>();
        for (ChatModel m : models) map.put(m.name(), m);
        ChatModelHttpSupport http = httpProvider.getIfAvailable();
        return new ChatModelRouter(map, props, http);
    }

    /**
     * LlmClient:Spring 容器 bean 入口。
     *
     * <p>{@link FunctionCallAggregator} 通过 {@link ObjectProvider} 注入 —— Step 11 加进来,
     * 缺失时降级为不聚合(理论上不应缺失,但作为防御写法保留)。
     */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient llmClient(ChatModelRouter router,
                               AiProperties props,
                               ObjectProvider<FunctionCallAggregator> aggregatorProvider) {
        FunctionCallAggregator aggregator = aggregatorProvider.getIfAvailable()
                != null ? aggregatorProvider.getIfAvailable() : new FunctionCallAggregator();
        return new LlmClient(router, props, aggregator);
    }

    /**
     * 共享 HTTP 支持(显式 @Bean)。
     *
     * <p>此前依赖类上的 {@code @Component} 注入;P4 Step 10 把
     * {@link AiAutoConfiguration} 当作"AI 模块 bean 清单"中心,这里显式 @Bean 让
     * {@link ConditionalOnMissingBean} 给出用户一个替换点(例如测试场景想塞个 mock
     * {@code ChatModelHttpSupport})。
     */
    @Bean
    @ConditionalOnMissingBean(ChatModelHttpSupport.class)
    public ChatModelHttpSupport chatModelHttpSupport(AiProperties props) {
        return new ChatModelHttpSupport(props);
    }

    /**
     * 启动日志:打印当前实际激活的 ChatModel 清单 + 配置摘要。
     *
     * <p>{@link CommandLineRunner} 在所有 bean 装配完成后、应用开始接受流量前执行;
     * 只读不写,出错也不影响启动。日志通过 {@link Logger} 输出到标准 logback,
     * 运维查问题时第一站即可看 AI 模块激活状态。
     *
     * <p>vendor 名直接从注入的 {@link ChatModel} bean 列表读 {@link ChatModel#name()},
     * 不直接 import 各 vendor ChatModel 类,避免模块间不必要的耦合。
     */
    @Bean
    public CommandLineRunner aiStartupLogger(List<ChatModel> models, AiProperties props) {
        return args -> {
            List<String> vendorNames = new ArrayList<>();
            for (ChatModel m : models) vendorNames.add(m.name());

            List<String> fallbackChain = props.getFallbackChain() == null
                    ? List.of() : props.getFallbackChain();

            log.info("[AI startup] defaultVendor={} defaultModel={} vendors={} fallbackChain={}",
                    props.getDefaultVendor(), props.getDefaultModel(),
                    vendorNames, fallbackChain);
        };
    }
}