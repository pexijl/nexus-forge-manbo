package com.nexusforge.ai.provider;

import com.nexusforge.ai.event.VendorConfigChangedEvent;
import com.nexusforge.ai.service.VendorConfigService;
import com.nexusforge.ai.service.VendorConfigService.VendorConfigView;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统 Key 路径 ChatModel 工厂(Phase 5 系统 Key 路径热重建,Phase 6 起 apiKey 也走 DB 热轮换)。
 *
 * <h3>为什么需要这个类</h3>
 * <p>Spring AI 2.0 OpenAI starter 的 {@code openAiChatModel} bean 是 {@code private
 * final} 字段的 {@code OpenAIClient}(构造期固定),per-call options 里的
 * {@code apiKey} / {@code baseUrl} 字段虽然存在,但 {@code OpenAiChatModel#internalCall}
 * 实际用的是 builder 构造时写入的 client — 也就是 starter 装配时的
 * {@code spring.ai.openai.api-key} / {@code spring.ai.openai.base-url}。
 *
 * <p>反编译 spring-ai-openai 2.0.0 源码确认(详见 commit 内部注释):
 * <ul>
 *   <li>{@code OpenAiChatModel.openAiClient} = {@code private final}</li>
 *   <li>client 在 builder.build() 阶段通过
 *       {@code OpenAiSetup.setupSyncClient(options.getBaseUrl(), options.getApiKey(), ...)} 一次性建好</li>
 *   <li>{@code internalCall} 走 {@code this.openAiClient.chat().completions().create(request)},
 *       client 引用永不变</li>
 * </ul>
 *
 * <p>所以 admin 改 {@code ai_vendor_config.base_url} 或 {@code encrypted_api_key}
 * 后,系统 Key 路径仍在用旧值 — 私 Key 路径(VendorChatModelFactory 每次 new 实例)
 * 没事,系统 Key 路径要重启。
 *
 * <h3>本类的解法</h3>
 * 绕过 starter 的固定 bean,自己按当前 vendor 配置动态构建 {@code OpenAiChatModel}。
 * 关键点:
 * <ol>
 *   <li>每次 call 查 {@code VendorConfigService.findByVendor(vendor)} — Caffeine 5min
 *       缓存(Phase 2 已建)+ 事件失效,微秒级返回</li>
 *   <li>按 (vendor, baseUrl, apiKey) 三元组算 fingerprint,缓存 {@code OpenAiChatModel} 实例
 *       — fingerprint 不变就不重建(避免每次 call 都 new RestClient)</li>
 *   <li>订阅 {@code VendorConfigChangedEvent},config 变了 → fingerprint 失效 →
 *       下次 call 重建并回填</li>
 * </ol>
 *
 * <h3>apiKey 来源(Phase 6 升级)</h3>
 * <ul>
 *   <li>Phase 5:仅 yaml {@code spring.ai.providers.<vendor>.api-key}(admin 改 DB 没意义)</li>
 *   <li>Phase 6:DB 优先 → yaml 兜底,通过 {@code VendorConfigService.getEffectiveApiKey(v)}
 *       拿明文;admin PUT {@code /api/admin/ai/vendors/{v}/api-key} 加密入库后,
 *       {@code VendorConfigChangedEvent} 触发本类清 cache,下次 call 走 DB 解密的新 key</li>
 * </ul>
 *
 * <h3>适用范围</h3>
 * OpenAI 协议家族(openai / deepseek / dashscope / glm / kimi / doubao / hunyuan /
 * siliconflow / oneapi / openrouter / minimax / ollama)— 全部走 {@code OpenAiChatModel}
 * 动态构造。Anthropic 私 Key 模式未实现 + Anthropic 系统 Key 路径不热重建(本类不覆盖,
 * 仍走 starter bean;Phase 6 之后视情况扩展)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemKeyChatModelFactory {

    private final VendorConfigService vendorConfigService;
    private final AiProperties props;

    /**
     * cacheKey = "vendor|baseUrl|sha256(apiKey)" → Spring AI ChatModel 缓存
     * <p>key 含 vendor 是因为 router 的 aliasing 让 openai / deepseek / dashscope 等
     * 都可能走到这里 — 每个 vendor 必须独立缓存(base_url 不同)
     * <p>apiKey 用 SHA-256 哈希做缓存键,原文不进入 key
     */
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * 按 vendor 拿当前配置的 ChatModel。优先走缓存;cache miss 时按当前
     * vendor config 动态构建。
     *
     * @param vendor vendor 名(小写,跟 router 解析出来的 key 一致)
     * @return 已配置好的 Spring AI ChatModel 实例
     * @throws LlmException vendor 未配置 / 禁用 / base URL 缺失
     */
    public ChatModel resolveOrCreate(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            throw new LlmException(ResultCode.LLM_INVALID_REQUEST, "vendor 不能为空");
        }
        String v = vendor.toLowerCase(Locale.ROOT);

        // 1. 读当前 vendor config(走 VendorConfigService 的 Caffeine 缓存,微秒级)
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

        // 2. 取 baseUrl(DB > yaml 兜底;VendorConfigView 已 merge)
        String baseUrl = view.entity().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                    "vendor=" + v + " 系统 Key 模式需 base URL(DB 或 yaml)");
        }

        // 3. 取 apiKey(Phase 6:DB > yaml 兜底,统一由 VendorConfigService.getEffectiveApiKey 出口)
        //    失败兜底到 props 上的 Provider.apiKey(理论上不会触发,getEffectiveApiKey 自己已 fallback 到 yaml)
        //    props 兜底存在的意义:即便 vendorConfigService 被 mock 掉,本类仍能装配不阻塞其他 vendor
        String apiKey = vendorConfigService.getEffectiveApiKey(v);
        if (apiKey == null || apiKey.isBlank()) {
            // 双兜底:再读一次 props(防止 VendorConfigService 实现改变)
            if (props.getProviders() != null) {
                AiProperties.Provider p = props.getProviders().get(v);
                if (p != null && p.getApiKey() != null && !p.getApiKey().isBlank()) {
                    apiKey = p.getApiKey();
                }
            }
        }
        if (apiKey == null || apiKey.isBlank()) {
            // OpenAI Java SDK 构造期强制至少一个 credential source,空串抛 IllegalStateException
            // 这里给占位符让 OpenAIChatModel 装配成功,真调用时上游返 401(不阻断其他 vendor)
            apiKey = "system-key-placeholder-" + v;
            log.warn("[SystemKeyChatModelFactory] vendor={} 的 apiKey 为空(DB + yaml 都没有),注入占位符(真调用会 401,不影响其他 vendor 路由)",
                    v);
        }

        // 4. 按 (vendor, baseUrl, apiKey) 算 fingerprint 查缓存
        String cacheKey = v + "|" + baseUrl + "|" + sha256Hex(apiKey);
        ChatModel cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("[SystemKeyChatModelFactory] cache hit vendor={} baseUrl={}", v, baseUrl);
            return cached;
        }

        // 5. 缓存未命中,build 新 OpenAiChatModel
        ChatModel created = buildOpenAiChatModel(baseUrl, apiKey);
        ChatModel prior = cache.putIfAbsent(cacheKey, created);
        ChatModel winner = prior != null ? prior : created;
        if (prior == null) {
            log.info("[SystemKeyChatModelFactory] 新建 ChatModel vendor={} baseUrl={}", v, baseUrl);
        }
        return winner;
    }

    /**
     * Phase 5 — admin 改 vendor config 后,本类自己缓存的 ChatModel 作废,要清掉。
     * <p>简化策略:任意 vendor config 变更 → 清全部 cache(私 Key 缓存规模小,几 vendor ×
     * 几 baseUrl 组合,清空性能可接受)。要精准按 vendor 清,反查要遍历 cache.values() —
     * 不值得。
     * <p>Phase 6 起 apiKey 改完也是同一事件(ClearApiKey / SetApiKey 都发 UPDATED),
     * 同一份逻辑覆盖。
     */
    @EventListener
    public void onVendorConfigChanged(VendorConfigChangedEvent ev) {
        log.info("[SystemKeyChatModelFactory] vendor={} 配置变更,清空本类 ChatModel 缓存(避免 baseUrl / apiKey 漂移)",
                ev.getVendor());
        invalidateAll();
    }

    public void invalidateAll() {
        if (cache.isEmpty()) return;
        int size = cache.size();
        cache.clear();
        log.info("[SystemKeyChatModelFactory] invalidateAll:清空 {} 个 ChatModel 缓存", size);
    }

    /**
     * 程序化构造 Spring AI {@link OpenAiChatModel} 实例。
     * <p>跟 {@link VendorChatModelFactory#buildOpenAiChatModel} 一样:把 baseUrl +
     * apiKey 写到 options,builder 内部调 {@code OpenAiSetup.setupSyncClient(options.getBaseUrl(),
     * options.getApiKey(), ...)} 构造 client — 这就是为什么"new OpenAiChatModel"就等于
     * "用这套 baseUrl + apiKey"。
     *
     * <p>超时 / maxRetries 等其他 OpenAI 参数走 Spring AI 默认;Phase 7 视情况加
     * ApplicationProperty 透传。
     */
    private static OpenAiChatModel buildOpenAiChatModel(String baseUrl, String apiKey) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
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
