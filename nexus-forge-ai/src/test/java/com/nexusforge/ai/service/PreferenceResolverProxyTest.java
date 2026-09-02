package com.nexusforge.ai.service;

import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.config.AiVendorRegistry;
import com.nexusforge.ai.entity.AiGlobalDefault;
import com.nexusforge.ai.entity.UserAiPreference;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Phase 3 — {@link PreferenceResolver} 优先级链 + 代理路径 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>proxyId 优先级最高(覆盖 model / 默认 proxy / preference / global)</li>
 *   <li>无 proxyId + 无 model + 有默认 proxy → 走默认 proxy,USER_PRIVATE_KEY</li>
 *   <li>无 proxyId + 有 model "vendor:model" + 用户有同 vendor 代理 → 用代理,USER_PRIVATE_KEY</li>
 *   <li>无 proxyId + 有 model "vendor:model" + 用户无同 vendor 代理 → SYSTEM</li>
 *   <li>无 proxyId + 无 model + 有默认 proxy 但 disabled → fall through 到 global</li>
 *   <li>无 proxyId + 无 model + 无默认 proxy + 有 preference → preference(legacy)</li>
 *   <li>无 proxyId + 无 model + 无 proxy + 无 preference → global</li>
 *   <li>model 决定优先级:proxy.defaultModel > vendor.yaml.defaultModel</li>
 *   <li>request model 显式带 vendor: → 解析为纯 model 名</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PreferenceResolver — Phase 3 proxyId 优先级 + 默认代理")
class PreferenceResolverProxyTest {

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

        // yaml: openai + deepseek + ollama 都启用
        props = new AiProperties();
        Map<String, AiProperties.Provider> providers = new HashMap<>();
        providers.put("openai",   provider("openai",   true,  "https://api.openai.com/v1", "gpt-4o-mini"));
        providers.put("deepseek", provider("deepseek", true,  "https://api.deepseek.com",  "deepseek-chat"));
        providers.put("ollama",   provider("ollama",   false, "http://localhost:11434/v1", "llama3.1"));
        props.setProviders(providers);

        // 默认 mock:global 默认已配置
        AiGlobalDefault g = new AiGlobalDefault();
        g.setVendor("openai");
        g.setModel("gpt-4o-mini");
        g.setEnabled(true);
        when(globalRepo.findById(1)).thenReturn(Optional.of(g));

        resolver = new PreferenceResolver(userPrefRepo, globalRepo, cipher, factory, props,
                vendorRegistry, userProxyService, userAliasService);
    }

    // ─────────────────── 优先级 1:proxyId 显式 ───────────────────

    @Nested
    @DisplayName("proxyId 显式")
    class ExplicitProxyId {

        @Test
        @DisplayName("proxyId 指向 enabled 代理 → USER_PRIVATE_KEY,带 vendor+baseUrl+apiKey+model")
        void explicit_proxy_wins() {
            UserAiProxy p = newProxy(10L, 7L, "我的 DeepSeek", "deepseek",
                    "https://api.deepseek.com/v1", "sk-decrypted-key-1234", true, "deepseek-v3", true);
            when(userProxyService.findById(7L, 10L)).thenReturn(p);

            // 即使有默认 proxy + preference,proxyId 也应胜出
            UserAiProxy defaultProxy = newProxy(20L, 7L, "默认", "openai",
                    "https://api.openai.com/v1", "sk-openai-key-xxxx", true, null, true);
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.of(defaultProxy));
            when(userPrefRepo.findById(7L)).thenReturn(Optional.of(legacyPreference(7L, "openai", "gpt-4o")));

            PreferenceResolver.Resolved r = resolver.resolve(7L, "deepseek-v3", 10L);

            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.model()).isEqualTo("deepseek-v3");
            assertThat(r.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
            assertThat(r.apiKey()).isEqualTo("sk-decrypted-key-1234");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.USER_PRIVATE_KEY);

            // 没走 preference / default proxy 路径
            verify(userPrefRepo, never()).findById(7L);
        }

        @Test
        @DisplayName("proxyId 指向 disabled 代理 → LLM_PROXY_DISABLED")
        void explicit_proxy_disabled_throws() {
            UserAiProxy p = newProxy(10L, 7L, "禁用代理", "deepseek",
                    "https://x", "sk-k", false, null, true);
            when(userProxyService.findById(7L, 10L)).thenReturn(p);

            assertThatThrownBy(() -> resolver.resolve(7L, null, 10L))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("已被禁用")
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_PROXY_DISABLED.getCode());
        }

        @Test
        @DisplayName("proxyId 指向不存在的代理 → LLM_PROXY_NOT_FOUND")
        void explicit_proxy_not_found_throws() {
            when(userProxyService.findById(7L, 99L))
                    .thenThrow(new com.nexusforge.exception.BusinessException(
                            ResultCode.LLM_PROXY_NOT_FOUND, "代理不存在"));

            assertThatThrownBy(() -> resolver.resolve(7L, null, 99L))
                    .isInstanceOf(com.nexusforge.exception.BusinessException.class)
                    .matches(e -> ((com.nexusforge.exception.BusinessException) e).getCode() == ResultCode.LLM_PROXY_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("proxyId + request model → model 决定最终 model(代理 defaultModel 不生效)")
        void explicit_model_overrides_proxy_default() {
            UserAiProxy p = newProxy(10L, 7L, "代理", "deepseek",
                    "https://x", "sk-k", true, "deepseek-v3", true);
            when(userProxyService.findById(7L, 10L)).thenReturn(p);

            PreferenceResolver.Resolved r = resolver.resolve(7L, "deepseek-v4-flash", 10L);

            assertThat(r.model()).isEqualTo("deepseek-v4-flash");  // request 胜出
        }
    }

    // ─────────────────── 优先级 2:model 显式 "vendor:model" ───────────────────

    @Nested
    @DisplayName("model 显式 (vendor:model)")
    class ExplicitModel {

        @Test
        @DisplayName("有 model 'deepseek:deepseek-v3' + 用户有 enabled deepseek 代理 → USER_PRIVATE_KEY(用代理 baseUrl+key)")
        void model_with_matching_proxy_uses_proxy() {
            UserAiProxy p = newProxy(10L, 7L, "代理", "deepseek",
                    "https://my-proxy.example.com/v1", "sk-my-key-xxx", true, null, true);
            when(userProxyService.listByUserId(7L)).thenReturn(List.of(p));

            PreferenceResolver.Resolved r = resolver.resolve(7L, "deepseek:deepseek-v3", null);

            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.model()).isEqualTo("deepseek-v3");
            assertThat(r.baseUrl()).isEqualTo("https://my-proxy.example.com/v1");
            assertThat(r.apiKey()).isEqualTo("sk-my-key-xxx");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.USER_PRIVATE_KEY);
        }

        @Test
        @DisplayName("有 model 'deepseek:deepseek-v3' + 用户无 deepseek 代理 → SYSTEM(走系统 Key,无 baseUrl/apiKey)")
        void model_without_matching_proxy_uses_system() {
            when(userProxyService.listByUserId(7L)).thenReturn(List.of());

            PreferenceResolver.Resolved r = resolver.resolve(7L, "deepseek:deepseek-v3", null);

            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.model()).isEqualTo("deepseek-v3");
            assertThat(r.baseUrl()).isNull();
            assertThat(r.apiKey()).isNull();
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.SYSTEM);
        }

        @Test
        @DisplayName("有 model 'openai:gpt-4o' 但 openai 代理 enabled=false → 走 SYSTEM(代理跳过)")
        void model_with_disabled_proxy_falls_through_to_system() {
            UserAiProxy p = newProxy(10L, 7L, "openai 代理", "openai",
                    "https://x", "sk-k", false, null, true);   // disabled
            when(userProxyService.listByUserId(7L)).thenReturn(List.of(p));

            PreferenceResolver.Resolved r = resolver.resolve(7L, "openai:gpt-4o", null);

            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.SYSTEM);
            assertThat(r.apiKey()).isNull();
        }

        @Test
        @DisplayName("model 'anthropic:claude-3' 但 vendor 不在 OpenAI 兼容集合且 yaml 未启用 → 抛 LLM_MODEL_NOT_FOUND")
        void model_with_unsupported_vendor_throws() {
            // anthropic 不在 OPENAI_COMPATIBLE_VENDORS 也不在 yaml providers → resolveFromRequest 抛错
            assertThatThrownBy(() -> resolver.resolve(7L, "anthropic:claude-3-opus", null))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }
    }

    // ─────────────────── 优先级 3:用户默认代理 ───────────────────

    @Nested
    @DisplayName("用户默认代理(无 proxyId / 无 model)")
    class DefaultProxy {

        @Test
        @DisplayName("用户默认代理 enabled + 配了 defaultModel → USER_PRIVATE_KEY,model 用 defaultModel")
        void default_proxy_with_default_model() {
            UserAiProxy p = newProxy(10L, 7L, "我的 DeepSeek", "deepseek",
                    "https://api.deepseek.com/v1", "sk-decrypted-key-1234", true, "deepseek-v3", true);
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.of(p));

            PreferenceResolver.Resolved r = resolver.resolve(7L, null, null);

            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.model()).isEqualTo("deepseek-v3");
            assertThat(r.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
            assertThat(r.apiKey()).isEqualTo("sk-decrypted-key-1234");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.USER_PRIVATE_KEY);
        }

        @Test
        @DisplayName("用户默认代理 enabled + 无 defaultModel → 走 vendor yaml default")
        void default_proxy_falls_back_to_yaml_default() {
            UserAiProxy p = newProxy(10L, 7L, "代理", "deepseek",
                    "https://x", "sk-k", true, null, true);   // defaultModel=null
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.of(p));

            PreferenceResolver.Resolved r = resolver.resolve(7L, null, null);

            assertThat(r.vendor()).isEqualTo("deepseek");
            assertThat(r.model()).isEqualTo("deepseek-chat");   // 来自 yaml providers.deepseek.default-model
        }

        @Test
        @DisplayName("用户默认代理 enabled=false → fall through 到 global")
        void default_proxy_disabled_falls_through() {
            UserAiProxy p = newProxy(10L, 7L, "禁用代理", "deepseek",
                    "https://x", "sk-k", false, "deepseek-v3", true);   // enabled=false
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.of(p));

            PreferenceResolver.Resolved r = resolver.resolve(7L, null, null);

            // fall through → global(已在 setUp mock:openai/gpt-4o-mini)
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o-mini");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.SYSTEM);
        }

        @Test
        @DisplayName("用户无默认代理 + 有 preference → preference 走 legacy")
        void no_default_proxy_uses_preference() {
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.empty());
            when(userPrefRepo.findById(7L)).thenReturn(Optional.of(legacyPreference(7L, "openai", "gpt-4o")));

            PreferenceResolver.Resolved r = resolver.resolve(7L, null, null);

            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.USER_OVERRIDE_SYSTEM_KEY);  // preference 无 key
        }

        @Test
        @DisplayName("用户无默认代理 + 有 preference + preference 有私 Key → USER_PRIVATE_KEY")
        void no_default_proxy_uses_preference_with_private_key() {
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.empty());
            UserAiPreference pref = legacyPreference(7L, "openai", "gpt-4o");
            pref.setEncryptedApiKey(cipher.encrypt("sk-pref-key-1234"));
            pref.setApiKeyFingerprint(cipher.fingerprint("sk-pref-key-1234"));
            when(userPrefRepo.findById(7L)).thenReturn(Optional.of(pref));

            PreferenceResolver.Resolved r = resolver.resolve(7L, null, null);

            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o");
            assertThat(r.apiKey()).isEqualTo("sk-pref-key-1234");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.USER_PRIVATE_KEY);
        }

        @Test
        @DisplayName("用户无默认代理 + 无 preference → global default")
        void no_default_proxy_no_preference_uses_global() {
            when(userProxyService.findDefaultByUserId(7L)).thenReturn(Optional.empty());
            when(userPrefRepo.findById(7L)).thenReturn(Optional.empty());

            PreferenceResolver.Resolved r = resolver.resolve(7L, null, null);

            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o-mini");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.SYSTEM);
        }

        @Test
        @DisplayName("匿名用户(没登录)→ 跳过所有 user 级,直接 global")
        void anonymous_uses_global() {
            PreferenceResolver.Resolved r = resolver.resolve(null, null, null);

            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.model()).isEqualTo("gpt-4o-mini");
            assertThat(r.source()).isEqualTo(PreferenceResolver.KeySource.SYSTEM);
            verify(userProxyService, never()).findDefaultByUserId(any());
            verify(userPrefRepo, never()).findById(any());
        }
    }

    // ─────────────────── helpers ───────────────────

    private static AiProperties.Provider provider(String name, boolean enabled, String baseUrl, String defaultModel) {
        AiProperties.Provider p = new AiProperties.Provider();
        // Provider 的 "name" 字段就是 yaml map 的 key,不通过 setter 赋值
        p.setEnabled(enabled);
        p.setBaseUrl(baseUrl);
        p.setDefaultModel(defaultModel);
        return p;
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
        // 密文:测试时 cipher 用 master-key 派生
        ApiKeyCipher c = new ApiKeyCipher("test-master-key-for-unit-tests-only", "");
        p.setEncryptedApiKey(c.encrypt(apiKeyPlain));
        return p;
    }

    private static UserAiPreference legacyPreference(Long userId, String vendor, String model) {
        UserAiPreference p = new UserAiPreference();
        p.setUserId(userId);
        p.setVendor(vendor);
        p.setModel(model);
        p.setEnabled(true);
        return p;
    }
}
