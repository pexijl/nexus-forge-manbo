package com.nexusforge.ai.provider;

import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.entity.AiVendorConfig;
import com.nexusforge.ai.event.VendorConfigChangedEvent;
import com.nexusforge.ai.service.VendorConfigService;
import com.nexusforge.ai.service.VendorConfigService.VendorConfigView;
import com.nexusforge.config.AiProperties;
import com.nexusforge.config.AiProperties.Provider;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatModel;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Phase 5 — {@link SystemKeyChatModelFactory} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>cache 命中:fingerprint 不变 → 不重建,返回同一实例</li>
 *   <li>cache 未命中:按 (vendor, baseUrl, apiKey) fingerprint build 新 OpenAiChatModel</li>
 *   <li>baseUrl 变化 → 新 fingerprint → 新实例(老实例被丢弃)</li>
 *   <li>apiKey 变化 → 新 fingerprint → 新实例(同理)</li>
 *   <li>vendor 不存在(VendorConfigView == null)→ 抛 LLM_CONFIG_MISSING</li>
 *   <li>vendor 禁用 → 抛 LLM_CONFIG_MISSING</li>
 *   <li>baseUrl 缺失 → 抛 LLM_CONFIG_MISSING</li>
 *   <li>apiKey 空(yaml 也没)→ 注入 placeholder,装配成功</li>
 *   <li>VendorConfigChangedEvent → invalidateAll,清全部 cache</li>
 *   <li>vendor 大小写归一化</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SystemKeyChatModelFactory — Phase 5 系统 Key 路径热重建")
class SystemKeyChatModelFactoryTest {

    @Mock VendorConfigService vendorConfigService;
    @Mock ApiKeyCipher cipher;

    private SystemKeyChatModelFactory factory;
    private AiProperties props;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        Map<String, Provider> providers = new HashMap<>();
        // yaml:openai 启用了 + 有 base-url + 有 api-key(占位符)
        providers.put("openai", provider("openai", true, "https://api.openai.com/v1", "sk-yaml-key"));
        providers.put("deepseek", provider("deepseek", true, "https://api.deepseek.com", "sk-deepseek-yaml"));
        // yaml:nokey vendor — baseUrl 有但 apiKey 缺(测占位符注入路径)
        providers.put("nokey", provider("nokey", true, "https://nokey.example.com", null));
        // yaml:disabled vendor
        providers.put("disabled-yaml", provider("disabled-yaml", false, "https://x", "sk-x"));
        props.setProviders(providers);

        // Phase 6 适配:VendorConfigService.getEffectiveApiKey 现在是 apiKey 单一入口
        // (DB 解密 → yaml 兜底 → null)。本测试走 yaml 兜底路径,所以 stub 让它从
        // AiProperties.Provider.getApiKey() 拿值。mock 简单实现:直接读 props 返回。
        when(vendorConfigService.getEffectiveApiKey(anyString()))
                .thenAnswer(inv -> {
                    String v = inv.getArgument(0);
                    Provider p = props.getProviders().get(v);
                    if (p == null) return null;
                    String k = p.getApiKey();
                    return (k == null || k.isBlank()) ? null : k;
                });

        factory = new SystemKeyChatModelFactory(vendorConfigService, props);
    }

    // ─────────────────── 正常路径 + 缓存 ───────────────────

    @Nested
    @DisplayName("cache 命中 / 重建")
    class Cache {

        @Test
        @DisplayName("首次调用:走 VendorConfigService + build OpenAiChatModel,缓存写入")
        void first_call_builds_and_caches() {
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(newCfg(1L, "openai", "https://api.openai.com/v1", true)));

            ChatModel m1 = factory.resolveOrCreate("openai");

            assertThat(m1).isNotNull();
            // OpenAiChatModel 是具体类(我们动态构造)
            assertThat(m1.getClass().getSimpleName()).isEqualTo("OpenAiChatModel");
            // 第二次 → 走 ChatModel cache(同一 fingerprint 直接返回)
            ChatModel m2 = factory.resolveOrCreate("openai");
            assertThat(m2).isSameAs(m1);
            // 关键:fingerprint 命中时只查 DB 一次后,后续每次 resolveOrCreate 都过 cache 复用
            // (VendorConfigService.findByVendor 也每次都查,因为本类不缓存 DB 结果 —
            //  DB 结果由 VendorConfigService 自己的 Caffeine 5min TTL 负责,这边不重复缓存)
            // 改用 verify(...).atLeast(1) 验证"至少查了一次",不锁死次数
            verify(vendorConfigService, atLeastOnce()).findByVendor("openai");
        }

        @Test
        @DisplayName("baseUrl 变化 → 新 fingerprint → 新实例")
        void baseUrl_change_rebuilds() {
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(newCfg(1L, "openai", "https://api.openai.com/v1", true)));
            ChatModel m1 = factory.resolveOrCreate("openai");

            // admin 改 baseUrl
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(newCfg(1L, "openai", "https://new-base.example.com/v1", true)));
            ChatModel m2 = factory.resolveOrCreate("openai");

            assertThat(m2).isNotSameAs(m1);
        }

        @Test
        @DisplayName("apiKey 变化(YAML 改)→ 新 fingerprint → 新实例")
        void apiKey_change_rebuilds() {
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(newCfg(1L, "openai", "https://api.openai.com/v1", true)));
            ChatModel m1 = factory.resolveOrCreate("openai");

            // 改 yaml apiKey(模拟 admin 改 env var + 重启;但 Phase 5 改 env var 仍需重启)
            // Phase 6 起 VendorConfigService.getEffectiveApiKey 是单一入口,
            // 改完它会返回新 key;fingerprint 变化 → 新 cache key → 重建
            when(vendorConfigService.getEffectiveApiKey("openai"))
                    .thenReturn("sk-NEW-yaml-key");
            ChatModel m2 = factory.resolveOrCreate("openai");

            assertThat(m2).isNotSameAs(m1);
        }

        @Test
        @DisplayName("vendor 大小写归一化:'OpenAI' / 'OPENAI' / 'openai' 命中同一 cache key")
        void case_insensitive() {
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(newCfg(1L, "openai", "https://api.openai.com/v1", true)));

            ChatModel m1 = factory.resolveOrCreate("OpenAI");
            ChatModel m2 = factory.resolveOrCreate("openai");
            ChatModel m3 = factory.resolveOrCreate("OPENAI");

            assertThat(m1).isSameAs(m2).isSameAs(m3);
            // 关键:所有调用都用 lowercase "openai" 查 VendorConfigService(归一化)
            verify(vendorConfigService, atLeastOnce()).findByVendor("openai");
            verify(vendorConfigService, never()).findByVendor("OpenAI");
            verify(vendorConfigService, never()).findByVendor("OPENAI");
        }
    }

    // ─────────────────── 错误路径 ───────────────────

    @Nested
    @DisplayName("错误路径")
    class ErrorPaths {

        @Test
        @DisplayName("vendor 不存在(VendorConfigService 返回 null)→ LLM_CONFIG_MISSING")
        void vendor_not_found_throws() {
            when(vendorConfigService.findByVendor("ghost")).thenReturn(null);

            assertThatThrownBy(() -> factory.resolveOrCreate("ghost"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("ghost")
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_CONFIG_MISSING.getCode());
        }

        @Test
        @DisplayName("vendor 禁用(enabled=false)→ LLM_CONFIG_MISSING")
        void vendor_disabled_throws() {
            when(vendorConfigService.findByVendor("disabled-yaml"))
                    .thenReturn(VendorConfigView.db(newCfg(2L, "disabled-yaml", "https://x", false)));

            assertThatThrownBy(() -> factory.resolveOrCreate("disabled-yaml"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("禁用")
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_CONFIG_MISSING.getCode());
        }

        @Test
        @DisplayName("baseUrl 缺失(为空)→ LLM_CONFIG_MISSING")
        void missing_base_url_throws() {
            when(vendorConfigService.findByVendor("no-url"))
                    .thenReturn(VendorConfigView.db(newCfg(3L, "no-url", "", true)));

            assertThatThrownBy(() -> factory.resolveOrCreate("no-url"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("base URL")
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_CONFIG_MISSING.getCode());
        }

        @Test
        @DisplayName("apiKey yaml 缺失 → 注入占位符(让 OpenAIChatModel 装配成功,不阻塞其他 vendor)")
        void missing_api_key_uses_placeholder() {
            when(vendorConfigService.findByVendor("nokey"))
                    .thenReturn(VendorConfigView.db(newCfg(4L, "nokey", "https://nokey.example.com", true)));

            // 不抛错,装配成功(真调用会 401)
            ChatModel m = factory.resolveOrCreate("nokey");
            assertThat(m).isNotNull();
        }

        @Test
        @DisplayName("vendor 留空 → LLM_INVALID_REQUEST")
        void blank_vendor_throws() {
            assertThatThrownBy(() -> factory.resolveOrCreate(""))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());

            assertThatThrownBy(() -> factory.resolveOrCreate(null))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
        }
    }

    // ─────────────────── 事件失效 ───────────────────

    @Nested
    @DisplayName("VendorConfigChangedEvent → 缓存失效")
    class EventInvalidation {

        @Test
        @DisplayName("admin 改 vendor config → 事件触发 → cache 清空 → 下次 call 重建")
        void event_clears_cache() {
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(newCfg(1L, "openai", "https://api.openai.com/v1", true)));
            ChatModel m1 = factory.resolveOrCreate("openai");

            // admin 改 enabled 或 baseUrl → 发事件
            AiVendorConfig changed = newCfg(1L, "openai", "https://api.openai.com/v1", true);
            factory.onVendorConfigChanged(new VendorConfigChangedEvent(this, changed,
                    VendorConfigChangedEvent.ChangeType.ENABLED_TOGGLED));

            // cache 已清空,下次 call 走 DB 重建
            // 模拟 admin 又改 baseUrl
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(newCfg(1L, "openai", "https://new.example.com/v1", true)));
            ChatModel m2 = factory.resolveOrCreate("openai");

            assertThat(m2).isNotSameAs(m1);   // 重建
        }

        @Test
        @DisplayName("invalidateAll 程序化调用 → 清空 cache")
        void invalidate_all_clears_cache() {
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(newCfg(1L, "openai", "https://api.openai.com/v1", true)));
            when(vendorConfigService.findByVendor("deepseek"))
                    .thenReturn(VendorConfigView.db(newCfg(2L, "deepseek", "https://api.deepseek.com", true)));

            factory.resolveOrCreate("openai");
            factory.resolveOrCreate("deepseek");
            // 2 个 cache 条目
            verify(vendorConfigService, times(1)).findByVendor("openai");
            verify(vendorConfigService, times(1)).findByVendor("deepseek");

            factory.invalidateAll();

            // 重建
            factory.resolveOrCreate("openai");
            factory.resolveOrCreate("deepseek");
            verify(vendorConfigService, times(2)).findByVendor("openai");
            verify(vendorConfigService, times(2)).findByVendor("deepseek");
        }
    }

    // ─────────────────── helpers ───────────────────

    private static Provider provider(String name, boolean enabled, String baseUrl, String apiKey) {
        Provider p = new Provider();
        p.setEnabled(enabled);
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        return p;
    }

    private static AiVendorConfig newCfg(Long id, String vendor, String baseUrl, boolean enabled) {
        AiVendorConfig m = new AiVendorConfig();
        m.setId(id);
        m.setVendor(vendor);
        m.setBaseUrl(baseUrl);
        m.setEnabled(enabled);
        return m;
    }
}
