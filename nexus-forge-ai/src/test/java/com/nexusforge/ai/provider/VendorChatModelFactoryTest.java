package com.nexusforge.ai.provider;

import com.nexusforge.ai.entity.AiVendorConfig;
import com.nexusforge.ai.event.VendorConfigChangedEvent;
import com.nexusforge.ai.service.VendorConfigService;
import com.nexusforge.ai.service.VendorConfigService.VendorConfigView;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 — {@link VendorChatModelFactory} 单元测试。
 *
 * <p>重点:验证 vendor enabled / baseUrl 从 {@link VendorConfigService} 读(DB 优先,
 * yaml 兜底);defaultModel 仍从 yaml。
 *
 * <p>注意:本测试<b>只验证路径选择逻辑</b>(enabled / baseUrl / defaultModel 来源)
 * 和事件触发的缓存清空,ChatModel 实际能不能调通是 Spring AI 的事。
 */
@DisplayName("VendorChatModelFactory — Phase 2 DB 优先")
class VendorChatModelFactoryTest {

    private VendorConfigService stubConfigService;
    private AiProperties yamlProps;
    private VendorChatModelFactory factory;

    @BeforeEach
    void setUp() {
        yamlProps = new AiProperties();
        Map<String, AiProperties.Provider> providers = new HashMap<>();
        providers.put("openai", provider(true, "https://yaml-openai.example.com", "gpt-4o-mini"));
        providers.put("deepseek", provider(true, "https://api.deepseek.com", "deepseek-v4-flash"));
        yamlProps.setProviders(providers);

        stubConfigService = new StubConfigService(yamlProps);
        factory = new VendorChatModelFactory(yamlProps, stubConfigService);
    }

    // ─────────── enabled 校验 ───────────

    @Test
    @DisplayName("vendor 在 DB 且 enabled=true → 正常构造 ChatModel")
    void db_enabled_proceeds() {
        // StubConfigService 配 openai enabled=true
        ChatModel m = factory.resolveOrCreate("openai", null, "sk-test");
        assertThat(m).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    @DisplayName("vendor 在 DB 但 enabled=false → 抛 LLM_CONFIG_MISSING,不进 ChatModel")
    void db_disabled_throws() {
        SpyConfigService spy = new SpyConfigService(yamlProps);
        spy.put("openai", viewFromDb("openai", "https://x", false));
        VendorChatModelFactory f = new VendorChatModelFactory(yamlProps, spy);

        assertThatThrownBy(() -> f.resolveOrCreate("openai", null, "sk-test"))
                .isInstanceOf(LlmException.class)
                .matches(e -> {
                    LlmException le = (LlmException) e;
                    return le.getCode() == ResultCode.LLM_CONFIG_MISSING.getCode()
                            && le.getMessage().contains("禁用");
                });
    }

    // ─────────── baseUrl 优先级 ───────────

    @Test
    @DisplayName("baseUrl 优先级:caller > DB > yaml")
    void baseUrl_precedence() {
        // 默认 stub 的 openai baseUrl="https://db-openai.example.com"
        // caller 传 null → 用 DB
        ChatModel m1 = factory.resolveOrCreate("openai", null, "sk-test");
        assertThat(m1).isNotNull();

        // caller 传 baseUrl → 覆盖 DB
        ChatModel m2 = factory.resolveOrCreate("openai", "https://caller.example.com", "sk-test");
        assertThat(m2).isNotNull();
        // 不同 baseUrl 应该是不同 cache key
        assertThat(m1).isNotSameAs(m2);
    }

    // ─────────── yaml fallback ───────────

    @Test
    @DisplayName("DB 没配 deepseek → 走 yaml 兜底,fromYaml=true")
    void yaml_fallback_for_unknown_db() {
        // stub 没 deepseek,DB findByVendor 返回 empty → 走 yaml
        ChatModel m = factory.resolveOrCreate("deepseek", null, "sk-test");
        assertThat(m).isNotNull();
    }

    @Test
    @DisplayName("DB + yaml 都没的 vendor → 抛 LLM_CONFIG_MISSING,提示 yaml 配了再 seed")
    void both_miss_throws() {
        assertThatThrownBy(() -> factory.resolveOrCreate("ghost", null, "sk-test"))
                .isInstanceOf(LlmException.class)
                .matches(e -> {
                    LlmException le = (LlmException) e;
                    return le.getCode() == ResultCode.LLM_CONFIG_MISSING.getCode()
                            && le.getMessage().contains("ai_vendor_config")
                            && le.getMessage().contains("yaml");
                });
    }

    // ─────────── defaultModel 校验 ───────────

    @Test
    @DisplayName("yaml 缺 defaultModel → 抛 LLM_CONFIG_MISSING(沿用原行为)")
    void yaml_missing_defaultModel_throws() {
        yamlProps.getProviders().get("openai").setDefaultModel(null);

        assertThatThrownBy(() -> factory.resolveOrCreate("openai", null, "sk-test"))
                .isInstanceOf(LlmException.class)
                .matches(e -> {
                    LlmException le = (LlmException) e;
                    return le.getCode() == ResultCode.LLM_CONFIG_MISSING.getCode()
                            && le.getMessage().contains("default-model");
                });
    }

    // ─────────── vendor 事件清缓存 ───────────

    @Test
    @DisplayName("vendor 配置变更事件:清空本类 ChatModel 缓存,下次构造用新 baseUrl")
    void event_listener_clears_cache() {
        // 第一次构造缓存进 cache
        ChatModel m1 = factory.resolveOrCreate("openai", null, "sk-test");
        assertThat(m1).isNotNull();

        // 触发 vendor 变更事件
        AiVendorConfig any = new AiVendorConfig();
        any.setVendor("openai");
        factory.onVendorConfigChanged(new VendorConfigChangedEvent(this, any,
                VendorConfigChangedEvent.ChangeType.UPDATED));

        // 再次构造 → 缓存被清,新实例
        ChatModel m2 = factory.resolveOrCreate("openai", null, "sk-test");
        assertThat(m2).isNotNull();
        // cache 已被清 → 不是同一对象
        assertThat(m1).isNotSameAs(m2);
    }

    // ─────────── 基础校验 ───────────

    @Test
    @DisplayName("vendor=null / 空 → LLM_INVALID_REQUEST")
    void null_vendor_throws() {
        assertThatThrownBy(() -> factory.resolveOrCreate(null, null, "sk-test"))
                .isInstanceOf(LlmException.class)
                .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
    }

    @Test
    @DisplayName("apiKey=null / 空 → LLM_INVALID_REQUEST")
    void null_apiKey_throws() {
        assertThatThrownBy(() -> factory.resolveOrCreate("openai", null, null))
                .isInstanceOf(LlmException.class)
                .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
    }

    @Test
    @DisplayName("anthropic vendor → LLM_INVALID_REQUEST(私 Key 暂不支持)")
    void anthropic_throws() {
        assertThatThrownBy(() -> factory.resolveOrCreate("anthropic", null, "sk-test"))
                .isInstanceOf(LlmException.class)
                .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
    }

    // ─────────── helper ───────────

    private static AiProperties.Provider provider(boolean enabled, String baseUrl, String defaultModel) {
        AiProperties.Provider p = new AiProperties.Provider();
        p.setEnabled(enabled);
        p.setBaseUrl(baseUrl);
        p.setDefaultModel(defaultModel);
        return p;
    }

    private static VendorConfigView viewFromDb(String vendor, String baseUrl, boolean enabled) {
        AiVendorConfig m = new AiVendorConfig();
        m.setVendor(vendor);
        m.setBaseUrl(baseUrl);
        m.setEnabled(enabled);
        return VendorConfigView.db(m);
    }

    /**
     * Stub VendorConfigService:
     * - "openai" → DB 命中(enabled=true, baseUrl=https://db-openai.example.com)
     * - 其他 → 走 yaml 兜底(从 test fixture 的 yamlProps 读,内联实现,不调 super)
     */
    private static class StubConfigService extends VendorConfigService {
        private final AiProperties yaml;
        StubConfigService(AiProperties yaml) {
            super(null, yaml, null, null, null);   // Phase 6:ApiKeyCipher 占位(null 不影响本 stub — 不调 cipher 相关方法);Phase 8 同理占位 auditLogger
            this.yaml = yaml;
        }
        @Override
        public VendorConfigView findByVendor(String vendor) {
            if (vendor == null) return null;
            if ("openai".equals(vendor)) {
                return viewFromDb("openai", "https://db-openai.example.com", true);
            }
            // yaml fallback:不调 super(避免 null repo NPE),直接读 this.yaml
            if (yaml != null && yaml.getProviders() != null) {
                AiProperties.Provider p = yaml.getProviders().get(vendor);
                if (p != null) return VendorConfigView.yamlFallback(p);
            }
            return null;
        }
    }

    /**
     * 灵活版 stub,支持自定义 DB 命中项,其他走 yaml(从外部 yamlProps 读)。
     * 不调 super(避免 null repo);yaml fallback 内联实现。
     */
    private static class SpyConfigService extends VendorConfigService {
        private final Map<String, VendorConfigView> db = new HashMap<>();
        private final AiProperties yaml;
        SpyConfigService(AiProperties yaml) { super(null, yaml, null, null, null); this.yaml = yaml; }
        void put(String vendor, VendorConfigView v) { db.put(vendor, v); }
        @Override
        public VendorConfigView findByVendor(String vendor) {
            if (vendor == null) return null;
            if (db.containsKey(vendor)) return db.get(vendor);
            // yaml fallback:不调 super,直接读 this.yaml
            if (yaml != null && yaml.getProviders() != null) {
                AiProperties.Provider p = yaml.getProviders().get(vendor);
                if (p != null) return VendorConfigView.yamlFallback(p);
            }
            return null;
        }
    }
}
