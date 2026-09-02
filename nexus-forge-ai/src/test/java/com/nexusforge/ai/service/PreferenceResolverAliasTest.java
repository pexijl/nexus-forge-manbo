package com.nexusforge.ai.service;

import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.config.AiVendorRegistry;
import com.nexusforge.ai.entity.AiGlobalDefault;
import com.nexusforge.ai.entity.UserAiModelAlias;
import com.nexusforge.ai.entity.UserAiProxy;
import com.nexusforge.ai.provider.VendorChatModelFactory;
import com.nexusforge.ai.repository.AiGlobalDefaultRepository;
import com.nexusforge.ai.repository.UserAiPreferenceRepository;
import com.nexusforge.config.AiProperties;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 4 — {@link PreferenceResolver} alias 解析路径 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>alias 命中 → 改写为 "vendor:model" → 继续 vendor:model 路径</li>
 *   <li>alias 命中 + 用户有同 vendor 代理 → USER_PRIVATE_KEY(用代理 baseUrl+key)</li>
 *   <li>alias 命中 + 用户无同 vendor 代理 → SYSTEM</li>
 *   <li>alias 未命中 → 静默 fall through 到原优先级(原 model 字符串继续)</li>
 *   <li>alias enabled=false → 静默 fall through(同未命中)</li>
 *   <li>alias 含冒号 → service 跳过查询,跟 vendor:model 路径互不干扰</li>
 *   <li>alias 命中后改写为 "vendor:model" 但 vendor 不支持 → 抛 LLM_MODEL_NOT_FOUND</li>
 *   <li>匿名用户 → 不查 alias,直接走原优先级</li>
 *   <li>model=null/blank → 跳过 alias 查,直接走 default 链</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PreferenceResolver — Phase 4 alias 解析")
class PreferenceResolverAliasTest {

    @Mock UserAiPreferenceRepository userPrefRepo;
    @Mock AiGlobalDefaultRepository globalRepo;
    @Mock VendorChatModelFactory factory;
    @Mock UserAiProxyService userProxyService;
    @Mock UserAiModelAliasService userAliasService;

    private PreferenceResolver resolver;
    private AiProperties props;
    private ApiKeyCipher cipher;
    private AiVendorRegistry vendorRegistry;

    @BeforeEach
    void setUp() {
        cipher = new ApiKeyCipher("test-master-key-for-unit-tests-only", "");
        vendorRegistry = new AiVendorRegistry();

        props = new AiProperties();
        Map<String, AiProperties.Provider> providers = new HashMap<>();
        providers.put("openai",   provider("openai",   true,  "https://api.openai.com/v1", "gpt-4o-mini"));
        providers.put("deepseek", provider("deepseek", true,  "https://api.deepseek.com",  "deepseek-chat"));
        props.setProviders(providers);

        // global 默认
        AiGlobalDefault g = new AiGlobalDefault();
        g.setVendor("openai");
        g.setModel("gpt-4o-mini");
        g.setEnabled(true);
        when(globalRepo.findById(1)).thenReturn(Optional.of(g));

        resolver = new PreferenceResolver(userPrefRepo, globalRepo, cipher, factory, props,
                vendorRegistry, userProxyService, userAliasService);
    }

    // ─────────────────── alias 命中 → 改写 + 走原路径 ───────────────────

    @Nested
    @DisplayName("alias 命中")
    class AliasHit {

        @Test
        @DisplayName("alias '我的 GPT' 命中 → 改写为 'openai:gpt-4o-mini' → 走 vendor:model 路径")
        void hit_rewrites_and_uses_system_key() {
            UserAiModelAlias alias = newAlias(1L, 7L, "我的 GPT", "openai", "gpt-4o-mini", true);
            when(userAliasService.findByUserIdAndAlias(7L, "我的 GPT")).thenReturn(Optional.of(alias));
            when(userProxyService.listByUserId(7L)).thenReturn(java.util.List.of());  // 无代理

            PreferenceResolver.Resolved r = resolver.resolve(7L, "我的 GPT", null);

            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o-mini");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.SYSTEM);
            assertThat(r.apiKey()).isNull();
        }

        @Test
        @DisplayName("alias 命中 + 用户有同 vendor 代理 → USER_PRIVATE_KEY(自动走代理)")
        void hit_with_matching_proxy_uses_private_key() {
            UserAiModelAlias alias = newAlias(1L, 7L, "我的 DeepSeek", "deepseek", "deepseek-v3", true);
            when(userAliasService.findByUserIdAndAlias(7L, "我的 DeepSeek")).thenReturn(Optional.of(alias));
            UserAiProxy proxy = newProxy(10L, 7L, "代理", "deepseek",
                    "https://my-proxy.example.com", "sk-my-key-xxx", true, null, true);
            when(userProxyService.listByUserId(7L)).thenReturn(java.util.List.of(proxy));

            PreferenceResolver.Resolved r = resolver.resolve(7L, "我的 DeepSeek", null);

            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.model()).isEqualTo("deepseek-v3");
            assertThat(r.baseUrl()).isEqualTo("https://my-proxy.example.com");
            assertThat(r.apiKey()).isEqualTo("sk-my-key-xxx");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.USER_PRIVATE_KEY);
        }

        @Test
        @DisplayName("alias 命中 + 用户无同 vendor 代理 → SYSTEM(走系统 Key)")
        void hit_without_matching_proxy_uses_system() {
            UserAiModelAlias alias = newAlias(1L, 7L, "我的 GPT", "openai", "gpt-4o-mini", true);
            when(userAliasService.findByUserIdAndAlias(7L, "我的 GPT")).thenReturn(Optional.of(alias));
            when(userProxyService.listByUserId(7L)).thenReturn(java.util.List.of());

            PreferenceResolver.Resolved r = resolver.resolve(7L, "我的 GPT", null);

            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.SYSTEM);
        }

        @Test
        @DisplayName("alias target vendor 不支持(不在 OpenAI 兼容集合也不在 yaml)→ LLM_MODEL_NOT_FOUND")
        void alias_to_unsupported_vendor_throws() {
            UserAiModelAlias alias = newAlias(1L, 7L, "我的 Anthropic", "anthropic", "claude-3", true);
            when(userAliasService.findByUserIdAndAlias(7L, "我的 Anthropic")).thenReturn(Optional.of(alias));
            when(userProxyService.listByUserId(7L)).thenReturn(java.util.List.of());

            assertThatThrownBy(() -> resolver.resolve(7L, "我的 Anthropic", null))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }
    }

    // ─────────────────── alias 未命中 / disabled → fall through ───────────────────

    @Nested
    @DisplayName("alias 未命中 / disabled → 静默 fall through")
    class AliasMissOrDisabled {

        @Test
        @DisplayName("alias 不存在 → 静默 fall through(原 model 字符串继续原优先级)")
        void alias_not_found_falls_through() {
            when(userAliasService.findByUserIdAndAlias(7L, "不存在的")).thenReturn(Optional.empty());
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.empty());
            when(userPrefRepo.findById(7L)).thenReturn(Optional.empty());

            // "不存在的" 不含冒号,alias 查不到 → fall through 到 global default
            PreferenceResolver.Resolved r = resolver.resolve(7L, "不存在的", null);

            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o-mini");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.SYSTEM);
        }

        @Test
        @DisplayName("alias enabled=false → 静默 fall through(同未命中)")
        void alias_disabled_falls_through() {
            UserAiModelAlias alias = newAlias(1L, 7L, "草稿", "openai", "gpt-4o-mini", false);
            when(userAliasService.findByUserIdAndAlias(7L, "草稿")).thenReturn(Optional.of(alias));
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.empty());
            when(userPrefRepo.findById(7L)).thenReturn(Optional.empty());

            PreferenceResolver.Resolved r = resolver.resolve(7L, "草稿", null);

            // 走 global default
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("显式 'vendor:model' 格式 → 跳过 alias 查询,直接走 vendor:model 路径")
        void explicit_vendor_model_skips_alias() {
            // 不应该调 alias service
            when(userProxyService.listByUserId(7L)).thenReturn(java.util.List.of());

            PreferenceResolver.Resolved r = resolver.resolve(7L, "deepseek:deepseek-v3", null);

            // 调了 alias 吗?不应该
            verify(userAliasService, never()).findByUserIdAndAlias(any(), any());
            // 直接走 vendor:model 路径
            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.model()).isEqualTo("deepseek-v3");
        }

        @Test
        @DisplayName("model=null/blank → 跳过 alias,直接走 default 链")
        void null_model_skips_alias() {
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.empty());
            when(userPrefRepo.findById(7L)).thenReturn(Optional.empty());

            PreferenceResolver.Resolved r = resolver.resolve(7L, null, null);

            verify(userAliasService, never()).findByUserIdAndAlias(any(), any());
            // 走 global default
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("匿名用户 → 不查 alias,直接走 global")
        void anonymous_skips_alias() {
            PreferenceResolver.Resolved r = resolver.resolve(null, "我的 GPT", null);

            verify(userAliasService, never()).findByUserIdAndAlias(any(), any());
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o-mini");
        }
    }

    // ─────────────────── alias + proxy 优先级 ───────────────────

    @Nested
    @DisplayName("alias 与 proxyId / 默认 proxy 优先级")
    class AliasPriorityInteraction {

        @Test
        @DisplayName("proxyId 显式 > alias(改写):proxyId 命中时 alias 不查")
        void proxyId_takes_priority_over_alias() {
            when(userProxyService.findById(7L, 99L)).thenReturn(newProxy(
                    99L, 7L, "代理", "deepseek", "https://x", "sk-k", true, "deepseek-v3", true));

            PreferenceResolver.Resolved r = resolver.resolve(7L, "我的 GPT", 99L);

            // proxyId 胜出,alias 不查
            verify(userAliasService, never()).findByUserIdAndAlias(any(), any());
            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.USER_PRIVATE_KEY);
        }

        @Test
        @DisplayName("alias 命中 → 改写 → 触发同 vendor 代理查找,USER_PRIVATE_KEY(隐式代理)")
        void alias_rewrites_then_proxy_lookup_implicitly() {
            UserAiModelAlias alias = newAlias(1L, 7L, "我的 DeepSeek", "deepseek", "deepseek-v3", true);
            when(userAliasService.findByUserIdAndAlias(7L, "我的 DeepSeek")).thenReturn(Optional.of(alias));
            UserAiProxy proxy = newProxy(10L, 7L, "代理", "deepseek",
                    "https://my-proxy.example.com", "sk-my-key-xxx", true, null, true);
            when(userProxyService.listByUserId(7L)).thenReturn(java.util.List.of(proxy));

            PreferenceResolver.Resolved r = resolver.resolve(7L, "我的 DeepSeek", null);

            // alias 命中 + 改写为 deepseek:deepseek-v3 → 同 vendor 代理查找命中
            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.model()).isEqualTo("deepseek-v3");
            assertThat(r.apiKey()).isEqualTo("sk-my-key-xxx");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.USER_PRIVATE_KEY);
        }
    }

    // ─────────────────── helpers ───────────────────

    private static AiProperties.Provider provider(String name, boolean enabled, String baseUrl, String defaultModel) {
        AiProperties.Provider p = new AiProperties.Provider();
        p.setEnabled(enabled);
        p.setBaseUrl(baseUrl);
        p.setDefaultModel(defaultModel);
        return p;
    }

    private static UserAiModelAlias newAlias(Long id, Long userId, String alias, String vendor, String model, Boolean enabled) {
        UserAiModelAlias a = new UserAiModelAlias();
        try {
            var f = UserAiModelAlias.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (Exception e) { /* test only */ }
        a.setUserId(userId);
        a.setAlias(alias);
        a.setTargetVendor(vendor);
        a.setTargetModel(model);
        a.setEnabled(enabled);
        return a;
    }

    private static UserAiProxy newProxy(Long id, Long userId, String name, String vendor,
                                        String baseUrl, String apiKeyPlain,
                                        Boolean enabled, String defaultModel, Boolean isDefault) {
        UserAiProxy p = new UserAiProxy();
        try {
            var f = UserAiProxy.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (Exception e) { /* test only */ }
        p.setUserId(userId);
        p.setName(name);
        p.setVendor(vendor);
        p.setBaseUrl(baseUrl);
        p.setApiKeyFingerprint("fp-" + vendor);
        p.setEnabled(enabled);
        p.setDefaultModel(defaultModel);
        p.setIsDefault(isDefault);
        ApiKeyCipher c = new ApiKeyCipher("test-master-key-for-unit-tests-only", "");
        p.setEncryptedApiKey(c.encrypt(apiKeyPlain));
        return p;
    }
}
