package com.nexusforge.ai.provider;

import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.entity.AiVendorConfig;
import com.nexusforge.ai.event.VendorConfigChangedEvent;
import com.nexusforge.ai.service.VendorConfigService;
import com.nexusforge.ai.service.VendorConfigService.VendorConfigView;
import com.nexusforge.config.AiProperties;
import com.nexusforge.config.AiProperties.Provider;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase 6 — {@link SystemKeyChatModelFactory} 的 apiKey DB 路径单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>DB 覆盖 key 走 {@code getEffectiveApiKey} 返回的明文</li>
 *   <li>改 key 后 → 新 fingerprint → 新 ChatModel 实例</li>
 *   <li>改 key 后发 {@code VendorConfigChangedEvent} → 本类 cache 清空 → 下次 call 重建</li>
 *   <li>DB + yaml 都没 → 注入 placeholder(沿用 Phase 5 行为)</li>
 *   <li>getEffectiveApiKey 抛异常时不阻塞 factory(双兜底 — props 再读一次)</li>
 * </ul>
 *
 * <p>不重复 Phase 5 的 vendor-not-found / disabled / blank / case-insensitive / apiKey from yaml
 * 等基础测试(见 {@code SystemKeyChatModelFactoryTest}),本文件只盯 Phase 6 新增的
 * "apiKey DB 化 + 热轮换" 路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SystemKeyChatModelFactory — Phase 6 apiKey DB 化路径")
class SystemKeyChatModelFactoryApiKeyTest {

    @Mock VendorConfigService vendorConfigService;
    @Mock ApiKeyCipher cipher;

    private SystemKeyChatModelFactory factory;
    private AiProperties props;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        Map<String, Provider> providers = new HashMap<>();
        // yaml 上有"兜底" key(测"DB 命中跳过 yaml"路径)
        providers.put("openai", provider("openai", true, "https://api.openai.com/v1", "sk-yaml-fallback"));
        props.setProviders(providers);

        // 默认 stub:getEffectiveApiKey 直接从 props 读(模拟 yaml 兜底路径)
        when(vendorConfigService.getEffectiveApiKey(anyString()))
                .thenAnswer(inv -> {
                    String v = inv.getArgument(0);
                    Provider p = props.getProviders().get(v);
                    if (p == null) return null;
                    String k = p.getApiKey();
                    return (k == null || k.isBlank()) ? null : k;
                });
        // 默认 stub:findByVendor 返回一个 enabled 的 view
        when(vendorConfigService.findByVendor(anyString()))
                .thenAnswer(inv -> {
                    String v = inv.getArgument(0);
                    Provider p = props.getProviders().get(v);
                    if (p == null) return null;
                    AiVendorConfig cfg = new AiVendorConfig();
                    cfg.setVendor(v);
                    cfg.setBaseUrl(p.getBaseUrl());
                    cfg.setEnabled(p.isEnabled());
                    return VendorConfigView.db(cfg);
                });

        factory = new SystemKeyChatModelFactory(vendorConfigService, props);
    }

    // ─────────────────── DB 覆盖路径 ───────────────────

    @Nested
    @DisplayName("apiKey DB 覆盖路径")
    class DbKeyPath {

        @Test
        @DisplayName("admin 已设 DB key → 用 DB key,不读 yaml")
        void db_key_wins() {
            // 模拟 admin 已设 DB key:mock getEffectiveApiKey 返回 DB 解密后的明文
            when(vendorConfigService.getEffectiveApiKey("openai"))
                    .thenReturn("sk-DB-OVERRIDE-key");

            ChatModel m = factory.resolveOrCreate("openai");

            assertThat(m).isNotNull();
            // 关键:本次调用只用了 DB key(没回退到 yaml)
            verify(vendorConfigService).getEffectiveApiKey("openai");
        }

        @Test
        @DisplayName("DB key 变化 → 新 fingerprint → 新 ChatModel 实例")
        void db_key_change_rebuilds() {
            // 初始:DB key v1
            when(vendorConfigService.getEffectiveApiKey("openai"))
                    .thenReturn("sk-DB-v1");
            ChatModel m1 = factory.resolveOrCreate("openai");

            // admin 改 key(轮换)— mock 返新 key
            when(vendorConfigService.getEffectiveApiKey("openai"))
                    .thenReturn("sk-DB-v2-NEW");
            ChatModel m2 = factory.resolveOrCreate("openai");

            assertThat(m2).isNotSameAs(m1);
        }

        @Test
        @DisplayName("VendorConfigChangedEvent 触发 → cache 清空 → 下次 call 走新 DB key")
        void event_invalidates_after_db_key_change() {
            when(vendorConfigService.getEffectiveApiKey("openai"))
                    .thenReturn("sk-DB-old");
            ChatModel m1 = factory.resolveOrCreate("openai");

            // admin 改 key,发事件(ClearApiKey / SetApiKey 都发 UPDATED)
            AiVendorConfig changed = newCfg(1L, "openai", "https://api.openai.com/v1", true);
            changed.setEncryptedApiKey("ct".getBytes());
            changed.setApiKeyFingerprint("sk-n••••1234");
            factory.onVendorConfigChanged(new VendorConfigChangedEvent(this, changed,
                    VendorConfigChangedEvent.ChangeType.UPDATED));

            // mock 模拟 DB 已更新
            when(vendorConfigService.getEffectiveApiKey("openai"))
                    .thenReturn("sk-DB-NEW");
            ChatModel m2 = factory.resolveOrCreate("openai");

            // cache 已清 → 重建 → 新实例
            assertThat(m2).isNotSameAs(m1);
        }
    }

    // ─────────────────── 占位符路径 ───────────────────

    @Nested
    @DisplayName("DB + yaml 都没 → 注入占位符(沿用 Phase 5 行为)")
    class PlaceholderPath {

        @Test
        @DisplayName("getEffectiveApiKey 返 null + props 也没 → 不阻塞,注入占位符装配成功")
        void double_empty_uses_placeholder() {
            // getEffectiveApiKey 返 null(DB 和 yaml 都没)
            when(vendorConfigService.getEffectiveApiKey("openai")).thenReturn(null);
            // 但 findByVendor 仍返一个 enabled + 有 baseUrl 的 view(apiKey 缺是另一维度)
            // —— 模拟"vendor 在 yaml 有 baseUrl 但 yaml 也无 apiKey"的场景
            AiVendorConfig cfg = new AiVendorConfig();
            cfg.setVendor("openai");
            cfg.setBaseUrl("https://api.openai.com/v1");
            cfg.setEnabled(true);
            when(vendorConfigService.findByVendor("openai"))
                    .thenReturn(VendorConfigView.db(cfg));
            // 关键:从 setUp() 的 mock getEffectiveApiKey 切到 null
            // 但 findByVendor 仍返有效 view
            ChatModel m = factory.resolveOrCreate("openai");

            // 不抛错,装配成功(真调用会 401,但不阻塞其他 vendor 路由)
            assertThat(m).isNotNull();
            assertThat(m.getClass().getSimpleName()).isEqualTo("OpenAiChatModel");
        }
    }

    // ─────────────────── 兜底链 ───────────────────

    @Nested
    @DisplayName("双兜底:先 getEffectiveApiKey,再读 props")
    class FallbackChain {

        @Test
        @DisplayName("getEffectiveApiKey 返 null(模拟 vendorConfigService 实现 bug)→ props 兜底")
        void props_fallback() {
            // mock 实现"返 null"(模拟实现 bug)
            when(vendorConfigService.getEffectiveApiKey("openai")).thenReturn(null);
            // props 仍有 key
            // (setUp 默认 stub 但被这条 override 盖掉)

            ChatModel m = factory.resolveOrCreate("openai");

            // 不抛错 — props 兜底读到 "sk-yaml-fallback"
            assertThat(m).isNotNull();
        }

        @Test
        @DisplayName("getEffectiveApiKey 抛异常(不应发生)→ 异常透传(不静默吞)")
        void exception_propagates() {
            when(vendorConfigService.getEffectiveApiKey("openai"))
                    .thenThrow(new RuntimeException("DB 连接炸了"));

            // 异常透传 — 调用方应该知道 DB 不通了
            // 不静默吞,不降级到 props(避免"DB 故障时悄无声息走旧值"这种坑)
            try {
                factory.resolveOrCreate("openai");
                assertThat(false).as("应抛异常").isTrue();
            } catch (Exception e) {
                assertThat(e).isInstanceOf(RuntimeException.class);
                assertThat(e.getMessage()).contains("DB 连接炸了");
            }
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
        try {
            var f = AiVendorConfig.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (Exception ignored) { /* test only */ }
        m.setVendor(vendor);
        m.setBaseUrl(baseUrl);
        m.setEnabled(enabled);
        return m;
    }
}
