package com.nexusforge.router;

import com.nexusforge.ai.service.FallbackChainService;
import com.nexusforge.config.AiProperties;
import com.nexusforge.config.AiProperties.Provider;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * spring-ai-full-migration Phase 2a + DeepSeek 移除 — Spring AI 化 vendor 路由。
 *
 * <p>从 {@code com.nexusforge.model.ChatModel} 切到 Spring AI 的
 * {@link org.springframework.ai.chat.model.ChatModel},从我们 {@code ChatRequest}
 * 切到 Spring AI 的 {@link Prompt}。
 *
 * <p>核心行为:
 * <ul>
 *   <li>vendor 名 → ChatModel 索引</li>
 *   <li><b>OpenAI 兼容 vendor aliasing</b>:yaml 配的 vendor key(如
 *       {@code deepseek} / {@code dashscope} / {@code glm} / ...),如果
 *       没有自己的 ChatModel bean,会按 protocol 推断别名到对应 starter 的
 *       ChatModel bean(详见 {@link #aliasOpenAiCompatibleVendors})。这是
 *       DeepSeek 改走 OpenAI starter(commit 1)的关键支撑 — yaml 里继续用
 *       {@code providers.deepseek.*} 配置,运行时 vendor="deepseek" 自动
 *       路由到 openAiChatModel bean。</li>
 *   <li>降级链展开(按 {@code spring.ai.fallback-chain} 顺序)</li>
 *   <li>熔断查询 — Phase 4 暂时退化为"不查熔断",Phase 5 切到 Spring AI
 *       原生重试 / Resilience4j 时再补</li>
 *   <li>"触发降级"的错误码白名单(LLM_PROVIDER_ERROR / LLM_UPSTREAM_TIMEOUT)</li>
 * </ul>
 */
@Slf4j
public class ChatModelRouter {

    /**
     * 所有已加载的 Spring AI 大模型 bean(含 aliasing 后的别名条目)。
     * key:vendor 名(小写,如 {@code openai} / {@code deepseek}),value:Spring AI 的 ChatModel 实例。
     *
     * <p>Phase 5 起:ctor 接 {@code Map<String, ChatModel>}(key 是 Spring bean 名,
     * 如 {@code openAiChatModel}),构造时通过 {@link #normalizeBeanName} 归一化为
     * 小写 vendor 名。Spring AI 官方 starter 的 bean 名遵循 {@code <vendor>ChatModel}
     * 命名(OpenAI/Anthropic/Ollama 是 camelCase),所以归一化逻辑就是"剥
     * {@code ChatModel} 后缀 + 转小写"。
     *
     * <p>DeepSeek 移除 starter 后,Spring 容器只注入 3 个 ChatModel bean
     * ({@code openAiChatModel} / {@code anthropicChatModel} / {@code ollamaChatModel})。
     * 但 yaml 的 {@code providers.deepseek.*} 仍可正常配置,Phase 5 commit 后续的
     * {@code aliasOpenAiCompatibleVendors} 把 "deepseek" vendor key 别名到
     * openAiChatModel bean,业务面无感。
     */
    private final Map<String, ChatModel> models;

    /** AI 全局配置(yaml 的 defaultVendor / providers[vendor].defaultModel 等) */
    private final AiProperties props;

    /**
     * Phase 7 — 降级链数据源。读 DB → yaml 兜底;admin 改完立即生效(走事件 + cache 失效)。
     * 替代原先直接读 {@code props.getFallbackChain()},让 fallback chain 也能热改。
     */
    private final FallbackChainService fallbackChainService;

    public ChatModelRouter(Map<String, ChatModel> modelsByBeanName,
                           AiProperties props,
                           FallbackChainService fallbackChainService) {
        this.props = props;
        this.fallbackChainService = fallbackChainService;
        this.models = new HashMap<>();
        for (Map.Entry<String, ChatModel> e : modelsByBeanName.entrySet()) {
            this.models.put(normalizeBeanName(e.getKey()), e.getValue());
        }
        // Phase 5 + DeepSeek 移除:OpenAI 兼容 vendor aliasing
        // yaml 配 deepseek / dashscope / glm / ... 等 OpenAI 兼容 vendor,实际
        // 只在 spring-ai-starter-model-openai starter 装配出一个 openAiChatModel
        // bean — 这里把所有 enabled 且走 OPENAI 协议的 vendor key 别名到该 bean。
        aliasOpenAiCompatibleVendors();
    }

    /** 当前路由能看到的 vendor 名集合(不可变,小写)。启动日志 / 调试用。 */
    public java.util.Set<String> vendorNames() {
        return java.util.Collections.unmodifiableSet(models.keySet());
    }

    /**
     * Spring bean 名 → 内部 vendor 名。{@code openAiChatModel → openai} /
     * {@code deepSeekChatModel → deepseek} / {@code ollamaChatModel → ollama} /
     * {@code anthropicChatModel → anthropic}。
     */
    private static String normalizeBeanName(String beanName) {
        if (beanName == null) return "";
        if (beanName.endsWith("ChatModel")) {
            beanName = beanName.substring(0, beanName.length() - "ChatModel".length());
        }
        return beanName.toLowerCase(java.util.Locale.ROOT);
    }

    // ─────────────────────── resolve: 单 hop ───────────────────────

    /**
     * 把调用方传入的 (vendor, model) 路由到唯一 vendor。vendor / model 任一为
     * null 或空串时,落到 yaml 的 {@code default-vendor} / 对应 vendor 的
     * {@code default-model}。
     *
     * <p><b>设计要点</b>:vendor 跟 model 不再混在同一个字符串里传。原先
     * spring-ai-full-migration Phase 2a 用 {@code vendor:model} 拼在
     * {@code Prompt.getOptions().getModel()} 上让 router 自己反解 —— 问题是
     * Spring AI 透传该字段给上游 API,DeepSeek / OpenAI / Anthropic 等不认
     * {@code vendor:model} 这种格式,直接 400 Bad Request。修法是 router 显式
     * 接 (vendor, model) 两个参数,Prompt 的 model 字段只放纯模型名
     * (e.g. {@code "deepseek-v4-flash"})。
     */
    public Resolved resolve(String vendor, String model) {
        String v = (vendor == null || vendor.isBlank()) ? props.getDefaultVendor() : vendor;
        String m = (model == null || model.isBlank()) ? null : model;  // null = 让 resolveInternal 用 defaultModel
        return resolveInternal(v, m);
    }

    /**
     * 解析首选 + 按当前生效降级链(DB 或 yaml 兜底)展开降级链。
     * 链至少 1 项(首选),至多 1 + fallbackChain.size() 项(去重 + 跳过无效 vendor)。
     *
     * <p>vendor / model 任一为 null 或空串时,用 yaml 兜底(同
     * {@link #resolve(String, String)})。
     *
     * <p><b>Phase 7</b>:降级链数据源从 {@code props.getFallbackChain()} 切到
     * {@code FallbackChainService.findEffective()}(DB → yaml → empty 三段语义),
     * admin 通过 {@code PUT/DELETE /api/admin/ai/fallback-chain} 热改降级链,
     * 改完下次 call 即生效(走 {@code FallbackChainChangedEvent} 失效 cache)。
     */
    public FallbackChain resolveWithFallback(String vendor, String model) {
        Resolved primary = resolve(vendor, model);  // 解析失败立刻抛
        List<Resolved> chain = new ArrayList<>();
        chain.add(primary);

        // Phase 7 — 读 DB-优先降级链(yaml 兜底在 service 内部完成)
        List<String> effectiveChain = fallbackChainService.findEffective().vendors();
        if (effectiveChain != null) {
            for (String fbVendor : effectiveChain) {
                if (fbVendor == null || fbVendor.isBlank()) continue;
                String v = fbVendor.trim();
                if (containsVendor(chain, v)) continue;
                ChatModel impl = models.get(v.toLowerCase());
                if (impl == null) continue;
                AiProperties.Provider p = props.getProviders().get(v);
                if (p == null || !p.isEnabled()) continue;
                chain.add(new Resolved(impl, v, p.getDefaultModel()));
            }
        }

        return new FallbackChain(List.copyOf(chain), primary.vendor());
    }

    /**
     * "触发降级"白名单 — 仅 {@link ResultCode#LLM_PROVIDER_ERROR} /
     * {@link ResultCode#LLM_UPSTREAM_TIMEOUT} 触发。
     * (历史:此判断原先与 {@code ChatModelHttpSupport.isRetryable} 共享常量,
     * Phase 4 删 ChatModelHttpSupport 后保持同一白名单。)
     */
    public static boolean isFallbackTriggering(LlmException ex, String vendor) {
        if (ex == null) return false;
        ResultCode code = ResultCode.fromCodeValue(ex.getCode());
        return code == ResultCode.LLM_PROVIDER_ERROR
                || code == ResultCode.LLM_UPSTREAM_TIMEOUT;
    }

    /**
     * 首选 vendor 是否已经在熔断中。
     *
     * <p>Phase 4 暂不实现熔断(原 {@code ChatModelHttpSupport} 已删除,
     * Spring AI 的 retry / Resilience4j 留到 Phase 5 评估)。当前固定返回
     * {@code false} — 保留方法签名是为了不破坏 {@code LlmClient} 调用点。
     */
    public boolean isPrimaryVendorOpen(Resolved primary) {
        return false;
    }

    // ─────────────────────── 内部 ───────────────────────

    /**
     * OpenAI 兼容 vendor aliasing — yaml 配置里 enabled=true 且走 OPENAI 协议的
     * vendor key,如果 {@link #models} 里没有同名条目,会按 protocol 推断别名到
     * 对应 starter 的 ChatModel bean。
     *
     * <p>典型场景(DeepSeek 移除独立 starter 后):
     * <ul>
     *   <li>Spring 容器注入 {@code openAiChatModel} / {@code anthropicChatModel}
     *       / {@code ollamaChatModel} 3 个 bean</li>
     *   <li>yaml 的 {@code providers.deepseek.*} 由 ProviderPropertiesBridge 桥到
     *       {@code spring.ai.openai.*},openai starter 用 deepseek 的 base-url /
     *       api-key 装配 {@code openAiChatModel} bean</li>
     *   <li>本方法把 {@code models} 里的 {@code openai} 别名到 {@code deepseek} key
     *       (以及 yaml 配的 dashscope / glm / minimax 等所有 OPENAI 协议 vendor),
     *       这样 {@code resolve("deepseek", ...)} 不会抛 LLM_MODEL_NOT_FOUND,
     *       而是直接路由到 openaiChatModel bean</li>
     * </ul>
     *
     * <p>语义约束:
     * <ul>
     *   <li>yaml 里有但 map 里没有的 vendor key 才做 alias(已有自己的 bean 不覆盖)</li>
     *   <li>只 alias 启用了的 vendor({@code providers.<v>.enabled=true})</li>
     *   <li>协议推断走 {@link AiProperties#resolveProtocol};OPENAI 协议别名到
     *       {@code openai} ChatModel,ANTHROPIC → {@code anthropic},OLLAMA →
     *       {@code ollama}</li>
     *   <li>目标 bean 不存在时(例如 yaml 配了 anthropic 但没装 anthropic starter)
     *       不 alias,resolve 时自然抛 LLM_MODEL_NOT_FOUND</li>
     * </ul>
     *
     * <p><b>多 vendor 共用 bean 的限制</b>:openai 协议只有 1 个 ChatModel bean
     * (openAiChatModel),如果 yaml 同时启用 {@code providers.openai} 和
     * {@code providers.deepseek},两者都会别名到同一个 bean — 哪个 yaml 段的
     * 配置被 bridge 写到 {@code spring.ai.openai.*} 就用哪个(bridge addFirst
     * 后写覆盖先写,迭代顺序按 yaml 解析顺序)。业务上建议同协议只启用一个 vendor。
     */
    private void aliasOpenAiCompatibleVendors() {
        for (Map.Entry<String, Provider> entry : props.getProviders().entrySet()) {
            String vendorKey = entry.getKey();
            if (vendorKey == null || vendorKey.isBlank()) continue;
            String normalizedKey = vendorKey.toLowerCase(Locale.ROOT);
            if (models.containsKey(normalizedKey)) continue;  // 已有自己的 ChatModel bean,跳过
            Provider p = entry.getValue();
            if (p == null || !p.isEnabled()) continue;        // 禁用 vendor 不 alias

            AiProperties.Protocol protocol = props.resolveProtocol(vendorKey);
            String aliasKey = switch (protocol) {
                case OPENAI -> "openai";
                case ANTHROPIC -> "anthropic";
                case OLLAMA -> "ollama";
            };
            ChatModel aliased = models.get(aliasKey);
            if (aliased != null) {
                log.debug("[ChatModelRouter] aliasing vendor={} → {} (协议 OPENAI 兼容 vendor 共用 ChatModel bean)",
                        normalizedKey, aliasKey);
                models.put(normalizedKey, aliased);
            }
        }
    }

    private static boolean containsVendor(List<Resolved> chain, String vendor) {
        for (Resolved r : chain) {
            if (r.vendor().equalsIgnoreCase(vendor)) return true;
        }
        return false;
    }

    /**
     * 按指定 vendor + 实际 model 名解析为 Resolved。
     */
    private Resolved resolveInternal(String vendor, String model) {
        String vendorLower = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
        ChatModel impl = models.get(vendorLower);
        if (impl == null) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND, "未找到 vendor=" + vendor);
        }
        // props.getProviders() 的 key 是 yaml 解析后的字面 key(Spring Boot relaxed
        // binding 标准化为小写),跟 API 传入的 vendor 名做大小写不敏感匹配 — 否则
        // 用户传 "DEEPSEEK" / "DeepSeek" 这种大小写变体时,即使 models map 已
        // alias 成功,props 校验会误报 "vendor 已禁用"
        AiProperties.Provider p = null;
        for (Map.Entry<String, Provider> e : props.getProviders().entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(vendor)) {
                p = e.getValue();
                break;
            }
        }
        if (p == null || !p.isEnabled()) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND, "vendor=" + vendor + " 已禁用");
        }
        String effectiveModel = (model == null || model.isBlank()) ? p.getDefaultModel() : model;
        return new Resolved(impl, vendor, effectiveModel);
    }

    /**
     * 路由解析结果记录类。
     * {@code model} 字段是 Spring AI 的 {@link ChatModel}(底层被具体 starter 装配)。
     */
    public record Resolved(ChatModel model, String vendor, String modelName) {}

    /**
     * 降级链快照。{@link ChatModelRouter#resolveWithFallback} 时一次性构建;
     * 调用方按 {@link Iterable} 顺序依次尝试每一跳。
     */
    public record FallbackChain(List<Resolved> hops, String primaryVendor) implements Iterable<Resolved> {
        @Override
        public java.util.Iterator<Resolved> iterator() {
            return hops.iterator();
        }

        public int size() {
            return hops.size();
        }

        public boolean isSingleHop() {
            return hops.size() == 1;
        }
    }
}
