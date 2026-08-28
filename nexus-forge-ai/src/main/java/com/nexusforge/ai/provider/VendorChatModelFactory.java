package com.nexusforge.ai.provider;

import com.nexusforge.ai.config.AiVendorRegistry;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatModel;
import com.nexusforge.provider.openai.OpenAiCompatibleChatModel;
import com.nexusforge.provider.openai.OpenAiJsonMapper;
import com.nexusforge.stream.OpenAiStreamParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户私 Key 场景下的 ChatModel 动态工厂。
 *
 * <p>约束:**vendor 必须已在 yaml 的 {@code spring.ai.providers.<vendor>.enabled=true}**
 * 中启用。private 模式仅"覆盖"该 vendor 的 apiKey / baseUrl,不绕开 yaml 的存在性检查。
 *
 * <p>原因:仓库现有的 {@link OpenAiCompatibleChatModel} 构造会校验
 * {@code props.providers[vendor]} 存在且 enabled;绕开需重写大量协议层代码,得不偿失。
 * 私 Key 用户的 vendor 仍在管理员管控范围内,只是"用自己的 Key 跑同一个 vendor"。
 *
 * <p>缓存:同 (vendor, baseUrl, apiKey 哈希) 只构造一次,后续复用同一实例。
 * apiKey 用 SHA-256 哈希做缓存键,原文不进入 key(不会被日志 / 监控捕获)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorChatModelFactory {

    private final AiProperties props;
    private final ObjectMapper json;
    private final OpenAiStreamParser streamParser;
    /** 当前模块已注册的 vendor 列表(由 AiAutoConfiguration 注入),用于提供 vendor → defaultModel / defaultBaseUrl 映射 */
    private final AiVendorRegistry vendorRegistry;

    /** cacheKey → ChatModel 缓存 */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * 按 vendor + (可选 baseUrl) + apiKey 获取(或懒构造)一个 ChatModel。
     *
     * @param vendor  vendor 名(必须已在 yaml 启用)
     * @param baseUrl OpenAI 兼容协议 base URL(可为 null,fallback 到 yaml 配置或 vendor 默认值)
     * @param apiKey  API Key 明文
     * @return 已配置好的 ChatModel 实例
     */
    public ChatModel resolveOrCreate(String vendor, String baseUrl, String apiKey) {
        if (vendor == null || vendor.isBlank()) {
            throw new LlmException(ResultCode.LLM_INVALID_REQUEST, "vendor 不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException(ResultCode.LLM_INVALID_REQUEST, "私 Key 模式下 apiKey 不能为空");
        }
        AiProperties.Provider p = props.getProviders().get(vendor);
        if (p == null || !p.isEnabled()) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                    "vendor=" + vendor + " 未在 yaml 启用,私 Key 模式仍需系统侧启用该 vendor");
        }
        String effectiveBaseUrl = (baseUrl == null || baseUrl.isBlank())
                ? (p.getBaseUrl() != null ? p.getBaseUrl() : vendorRegistry.defaultBaseUrl(vendor))
                : baseUrl.trim();
        String effectiveDefaultModel = p.getDefaultModel() != null
                ? p.getDefaultModel()
                : vendorRegistry.defaultModel(vendor);

        String cacheKey = vendor.toLowerCase() + "|" + effectiveBaseUrl + "|" + sha256Hex(apiKey);
        ChatModel cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("[VendorChatModelFactory] cache hit vendor={} baseUrl={}", vendor, effectiveBaseUrl);
            return cached;
        }
        ChatModel created = new PrivateKeyOpenAiModel(vendor, effectiveBaseUrl, effectiveDefaultModel, apiKey,
                props, json, vendorRegistry.openAiJsonMapper(), streamParser);
        ChatModel prior = cache.putIfAbsent(cacheKey, created);
        ChatModel winner = prior != null ? prior : created;
        if (prior == null) {
            log.info("[VendorChatModelFactory] 新建 ChatModel vendor={} baseUrl={} model={}", vendor, effectiveBaseUrl, effectiveDefaultModel);
        }
        return winner;
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

    /**
     * 私 Key 模式专属 ChatModel。
     *
     * <p>复用 {@link OpenAiCompatibleChatModel} 全部 call/stream/降级语义;
     * 区别在于构造完成后 override {@code cfg.apiKey / cfg.baseUrl},绕过 yaml 的
     * 共享 Key 走用户私 Key。
     *
     * <p>本类显式声明为 static:避免 inner-class 隐式持有外层 {@link VendorChatModelFactory}
     * 引用,从而规避 Java "super(...) 调用前不能访问外层实例字段"的限制。
     * 所需依赖通过构造器参数显式传入。
     */
    private static final class PrivateKeyOpenAiModel extends OpenAiCompatibleChatModel {

        PrivateKeyOpenAiModel(String vendor,
                              String baseUrl,
                              String defaultModel,
                              String apiKey,
                              AiProperties props,
                              ObjectMapper json,
                              OpenAiJsonMapper mapper,
                              OpenAiStreamParser streamParser) {
            super(vendor, baseUrl, defaultModel, props, json, mapper, streamParser);
            // 覆盖:用用户私 Key
            cfg.setApiKey(apiKey);
        }
    }
}