package com.nexusforge.ai.provider;

import com.nexusforge.ai.event.VendorConfigChangedEvent;
import com.nexusforge.ai.service.VendorConfigService;
import com.nexusforge.ai.service.VendorConfigService.VendorConfigView;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * spring-ai-full-migration Phase 2b + 多模型管理 Phase 2 — 用户私 Key 场景下的
 * ChatModel 动态工厂。
 *
 * <h3>Phase 2 变化</h3>
 * <ul>
 *   <li>enabled + baseUrl 改从 {@link VendorConfigService} 读(ai_vendor_config
 *       表),DB 优先;DB 缺失回退 yaml</li>
 *   <li>defaultModel 仍从 yaml 读(Phase 2 不移到 DB,跟 model catalog 不重复:
 *       model catalog 是"哪些 model 可用",defaultModel 是"vendor 默认 model")</li>
 *   <li>admin 改 vendor baseUrl → 发 {@link VendorConfigChangedEvent} → 本类
 *       监听清自己的 ChatModel 缓存(baseUrl 改了旧的 ChatModel 失效)</li>
 * </ul>
 *
 * <p>约束:vendor 必须在 DB 或 yaml 启用。私 Key 模式仅"覆盖"该 vendor 的
 * apiKey / baseUrl,不绕开系统侧的存在性检查。
 *
 * <p>实现:
 * <ul>
 *   <li>OpenAI 协议家族(OpenAI / DeepSeek / Ollama / 中转站)— 程序化构造
 *       {@link OpenAiChatModel},每 (vendor, baseUrl, apiKey 哈希) 缓存一份实例</li>
 *   <li>Anthropic — 暂不实现私 Key 路径(私 Key 用 Anthropic 的需求极少见,
 *       留待后续)</li>
 * </ul>
 *
 * <p>缓存:同 (vendor, baseUrl, apiKey 哈希) 只构造一次,后续复用同一实例。
 * apiKey 用 SHA-256 哈希做缓存键,原文不进入 key(不会被日志 / 监控捕获)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorChatModelFactory {

    private final AiProperties props;
    private final VendorConfigService vendorConfigService;

    /** cacheKey → Spring AI ChatModel 缓存 */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * 按 vendor + (可选 baseUrl) + apiKey 获取(或懒构造)一个 Spring AI ChatModel。
     *
     * @param vendor  vendor 名(必须在 DB 或 yaml 启用)
     * @param baseUrl OpenAI 兼容协议 base URL(可为 null,fallback 到 DB 配置或 yaml 兜底)
     * @param apiKey  API Key 明文
     * @return 已配置好的 Spring AI ChatModel 实例
     */
    public ChatModel resolveOrCreate(String vendor, String baseUrl, String apiKey) {
        if (vendor == null || vendor.isBlank()) {
            throw new LlmException(ResultCode.LLM_INVALID_REQUEST, "vendor 不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException(ResultCode.LLM_INVALID_REQUEST, "私 Key 模式下 apiKey 不能为空");
        }
        String v = vendor.toLowerCase();
        // Anthropic 暂不实现私 Key
        if ("anthropic".equals(v)) {
            throw new LlmException(ResultCode.LLM_INVALID_REQUEST,
                    "Anthropic 私 Key 模式暂未实现,Phase 2b 后续补");
        }

        // Phase 2 — vendor 启用状态 + baseUrl 优先从 DB 读;DB 缺失 fallback yaml。
        // 缺一不可(DB 也没,yaml 也没 → vendor 不存在,直接拒绝)。
        VendorConfigView view = vendorConfigService.findByVendor(v);
        if (view == null) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                    "vendor=" + v + " 既不在 ai_vendor_config 也不在 yaml;请在 yaml 配 spring.ai.providers."
                            + v + ".base-url 后重启触发 seed");
        }
        if (Boolean.FALSE.equals(view.entity().getEnabled())) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                    "vendor=" + v + " 已被管理员禁用");
        }

        // baseUrl 三路优先级:调用方传 > DB 配置 > yaml 兜底(view 已 merge)
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank())
                ? baseUrl.trim()
                : view.entity().getBaseUrl();
        if (effectiveBaseUrl == null || effectiveBaseUrl.isBlank()) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                    "vendor=" + v + " 私 Key 模式需 base URL(DB 或 yaml)");
        }

        // defaultModel 仍在 yaml(Phase 2 不移)
        AiProperties.Provider p = props.getProviders() == null ? null : props.getProviders().get(v);
        String effectiveDefaultModel = p == null ? null : p.getDefaultModel();
        if (effectiveDefaultModel == null || effectiveDefaultModel.isBlank()) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                    "vendor=" + v + " 私 Key 模式需 yaml 配 spring.ai.providers." + v + ".default-model");
        }

        String cacheKey = v + "|" + effectiveBaseUrl + "|" + sha256Hex(apiKey);
        ChatModel cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("[VendorChatModelFactory] cache hit vendor={} baseUrl={}", v, effectiveBaseUrl);
            return cached;
        }
        ChatModel created = buildOpenAiChatModel(effectiveBaseUrl, apiKey, effectiveDefaultModel);
        ChatModel prior = cache.putIfAbsent(cacheKey, created);
        ChatModel winner = prior != null ? prior : created;
        if (prior == null) {
            log.info("[VendorChatModelFactory] 新建 ChatModel vendor={} baseUrl={} model={}",
                    v, effectiveBaseUrl, effectiveDefaultModel);
        }
        return winner;
    }

    /**
     * Phase 2 — admin 改 vendor base_url / enabled 后,本类自己的 ChatModel
     * 缓存(以 baseUrl 为 key 的一部分)作废,要清掉。
     *
     * <p>简化策略:vendor 变了就清空整个 cache(不是按 vendor 精准清)。
     * 原因:cache key 是 {@code vendor|baseUrl|sha256(apiKey)},反查要遍历;
     * private key cache 规模小(几个用户 × 几个 model),全部清空性能可接受。
     */
    @EventListener
    public void onVendorConfigChanged(VendorConfigChangedEvent ev) {
        log.info("[VendorChatModelFactory] vendor={} 配置变更,清空本类 ChatModel 缓存(避免 baseUrl 漂移)",
                ev.getVendor());
        cache.clear();
    }

    /**
     * Phase 3 — 用户 AI 代理变更后清空本类 ChatModel 缓存。
     *
     * <p>用户改自己代理的 {@code apiKey} / {@code baseUrl} / {@code vendor} 后,
     * 旧 ChatModel 持有旧配置,留着只会占内存 + 误调用。私 Key 缓存规模小
     * (几用户 × 几 model),无差别清空性能可接受。
     *
     * <p>跟 {@link #onVendorConfigChanged} 的差异:admin 改 vendor config 只影响
     * 该 vendor 的 cache 段,但 cache key 含 vendor 段,精准反查要遍历;
     * 一律清空是更简单的策略。同理用户代理变更也一律清空。
     */
    public void invalidateAll() {
        if (cache.isEmpty()) return;
        int size = cache.size();
        cache.clear();
        log.info("[VendorChatModelFactory] invalidateAll:清空 {} 个 ChatModel 缓存", size);
    }

    /**
     * 程序化构造 Spring AI {@link OpenAiChatModel} 实例。
     * 覆盖 OpenAI / DeepSeek / Qwen / Ollama / 中转站(都走 OpenAI Chat Completions 协议)。
     *
     * <p>Spring AI 2.0 的 {@link OpenAiChatOptions} 继承 {@code AbstractOpenAiOptions},
     * 后者直接提供 {@code apiKey(String)} / {@code baseUrl(String)} / {@code model(String)}
     * 工厂方法 — 私 Key 路径不需要单独的 {@code OpenAiApi} 客户端对象,直接通过
     * options 注入即可。
     *
     * <p>注意:Spring AI 2.0 把 {@code defaultOptions(...)} 改成了 {@code options(...)}
     * (后者是覆盖性赋值,前者是默认值合并),此处用 {@code options(...)}。
     */
    private static OpenAiChatModel buildOpenAiChatModel(String baseUrl, String apiKey, String defaultModel) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(defaultModel)
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .build())
                .build();
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bs = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bs.length * 2);
            for (byte b : bs) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
