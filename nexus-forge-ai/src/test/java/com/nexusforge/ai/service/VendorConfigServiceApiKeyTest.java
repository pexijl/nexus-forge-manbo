package com.nexusforge.ai.service;

import com.nexusforge.ai.audit.VendorApiKeyAuditEvent;
import com.nexusforge.ai.audit.VendorApiKeyAuditLogger;
import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.entity.AiVendorConfig;
import com.nexusforge.ai.enums.VendorApiKeyAuditAction;
import com.nexusforge.ai.event.VendorConfigChangedEvent;
import com.nexusforge.ai.repository.AiVendorConfigRepository;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase 6 — {@link VendorConfigService} 的 apiKey 路径单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>setApiKey:加密入库 + 发事件 + 同步写 fingerprint</li>
 *   <li>setApiKey:空字符串拒绝;vendor 不存在抛 404</li>
 *   <li>clearApiKey:两列置 NULL + 发事件</li>
 *   <li>getEffectiveApiKey:DB 解密 → yaml 兜底 → null</li>
 *   <li>getEffectiveApiKey:DB 解密失败降级到 yaml(不阻塞)</li>
 *   <li>seed 不再 seed apiKey(Phase 6 显式决定)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VendorConfigService — Phase 6 system apiKey 路径")
class VendorConfigServiceApiKeyTest {

    @Mock AiVendorConfigRepository repo;
    @Mock ApplicationEventPublisher publisher;
    @Mock ApiKeyCipher cipher;
    @Mock VendorApiKeyAuditLogger auditLogger;

    private VendorConfigService service;
    private AiProperties yamlProps;

    @BeforeEach
    void setUp() {
        yamlProps = new AiProperties();
        Map<String, AiProperties.Provider> providers = new HashMap<>();
        // 配 3 个 vendor:openai(测 DB 覆盖)+ ollama(测 yaml 兜底)+ ghost-yaml(测 DB miss → yaml 兜底)
        providers.put("openai", providerWithKey(true, "https://api.openai.com/v1", "sk-yaml-openai"));
        providers.put("ollama", providerWithKey(true, "http://localhost:11434/v1", "sk-yaml-ollama"));
        providers.put("ghost-yaml", providerWithKey(true, "https://x", "sk-yaml-ghost"));
        yamlProps.setProviders(providers);

        service = new VendorConfigService(repo, yamlProps, publisher, cipher, auditLogger);
    }

    // ─────────────────── setApiKey ───────────────────

    @Nested
    @DisplayName("setApiKey")
    class SetApiKey {

        @Test
        @DisplayName("DB 有 vendor:加密 + 算 fingerprint + 写两列 + 发事件")
        void happy_path() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://api.openai.com/v1", true);
            // 模拟 encrypt:byte[] 假密文;fingerprint 假指纹
            byte[] fakeCipher = "encrypted-bytes".getBytes();
            when(cipher.encrypt("sk-new-prod-key")).thenReturn(fakeCipher);
            when(cipher.fingerprint("sk-new-prod-key")).thenReturn("sk-n••••abcd1234");
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            AiVendorConfig saved = service.setApiKey("openai", "sk-new-prod-key", 1L, "127.0.0.1");

            assertThat(saved.getEncryptedApiKey()).isEqualTo(fakeCipher);
            assertThat(saved.getApiKeyFingerprint()).isEqualTo("sk-n••••abcd1234");
            verify(cipher).encrypt("sk-new-prod-key");
            verify(cipher).fingerprint("sk-new-prod-key");
            verify(repo).save(existing);

            ArgumentCaptor<VendorConfigChangedEvent> ev = ArgumentCaptor.forClass(VendorConfigChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(VendorConfigChangedEvent.ChangeType.UPDATED);
            assertThat(ev.getValue().getVendor()).isEqualTo("openai");
        }

        @Test
        @DisplayName("空字符串 / null 拒绝(LLM_INVALID_REQUEST)")
        void blank_rejected() {
            assertThatThrownBy(() -> service.setApiKey("openai", "", 1L, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
            assertThatThrownBy(() -> service.setApiKey("openai", "   ", 1L, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
            assertThatThrownBy(() -> service.setApiKey("openai", null, 1L, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
            verify(repo, never()).save(any());
            verify(cipher, never()).encrypt(anyString());
        }

        @Test
        @DisplayName("vendor 不在 DB → 抛 LLM_MODEL_NOT_FOUND(不写任何东西)")
        void vendor_not_found_throws() {
            when(repo.findByVendor("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setApiKey("ghost", "sk-xxx", 1L, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
            verify(repo, never()).save(any());
            verify(cipher, never()).encrypt(anyString());
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("vendor 大小写归一化:'OpenAI' 等价于 'openai'")
        void case_insensitive_vendor() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://x", true);
            when(cipher.encrypt("sk-x")).thenReturn("ct".getBytes());
            when(cipher.fingerprint("sk-x")).thenReturn("sk-x••••0000");
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            service.setApiKey("OpenAI", "sk-x", 1L, "127.0.0.1");

            verify(repo).findByVendor("openai");   // 小写查询
        }
    }

    // ─────────────────── clearApiKey ───────────────────

    @Nested
    @DisplayName("clearApiKey")
    class ClearApiKey {

        @Test
        @DisplayName("DB 有 vendor:两列置 NULL + 发事件")
        void happy_path() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://x", true);
            existing.setEncryptedApiKey("old-cipher".getBytes());
            existing.setApiKeyFingerprint("old-fp");
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            AiVendorConfig cleared = service.clearApiKey("openai", 1L, "127.0.0.1");

            assertThat(cleared.getEncryptedApiKey()).isNull();
            assertThat(cleared.getApiKeyFingerprint()).isNull();
            verify(repo).save(existing);

            ArgumentCaptor<VendorConfigChangedEvent> ev = ArgumentCaptor.forClass(VendorConfigChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(VendorConfigChangedEvent.ChangeType.UPDATED);
        }

        @Test
        @DisplayName("vendor 不在 DB → 抛 LLM_MODEL_NOT_FOUND")
        void vendor_not_found_throws() {
            when(repo.findByVendor("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.clearApiKey("ghost", 1L, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
            verify(repo, never()).save(any());
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("幂等:已清空(DB 两列都 null)再清 → 不报错,事件仍发")
        void idempotent() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://x", true);
            // 两列本来就 null
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            AiVendorConfig cleared = service.clearApiKey("openai", 1L, "127.0.0.1");

            assertThat(cleared.getEncryptedApiKey()).isNull();
            assertThat(cleared.getApiKeyFingerprint()).isNull();
            verify(publisher, times(1)).publishEvent(any(VendorConfigChangedEvent.class));
        }
    }

    // ─────────────────── Phase 8: audit 写入 ───────────────────

    @Nested
    @DisplayName("Phase 8: 每次写 apiKey 都同步写 ai_api_key_audit_log")
    class Phase8AuditLogging {

        @Test
        @DisplayName("setApiKey:auditLogger.log(SET) 一次,event 字段正确(actorId / fingerprint_before / after / requestIp)")
        void setApiKey_audit() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://x", true);
            // 改前 fingerprint 是 old(模拟"轮换"场景)
            existing.setApiKeyFingerprint("sk-old••••prev");
            byte[] fakeCipher = "ct".getBytes();
            when(cipher.encrypt("sk-new")).thenReturn(fakeCipher);
            when(cipher.fingerprint("sk-new")).thenReturn("sk-new••••curr");
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            service.setApiKey("openai", "sk-new", 42L, "10.0.0.1");

            ArgumentCaptor<VendorApiKeyAuditEvent> evCap =
                    ArgumentCaptor.forClass(VendorApiKeyAuditEvent.class);
            verify(auditLogger, times(1)).log(evCap.capture());
            VendorApiKeyAuditEvent e = evCap.getValue();
            assertThat(e.action()).isEqualTo(VendorApiKeyAuditAction.SET);
            assertThat(e.vendor()).isEqualTo("openai");
            assertThat(e.actorId()).isEqualTo(42L);
            assertThat(e.actorRole()).isEqualTo("ADMIN");
            assertThat(e.fingerprintBefore()).isEqualTo("sk-old••••prev");
            assertThat(e.fingerprintAfter()).isEqualTo("sk-new••••curr");
            assertThat(e.requestIp()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("setApiKey 首次装机:DB 行无 fingerprint → fingerprintBefore=null(可推断 '新装' vs '轮换')")
        void setApiKey_first_install_audit() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://x", true);
            // 不设 fingerprint,模拟"第一次装"
            when(cipher.encrypt("sk-first")).thenReturn("ct".getBytes());
            when(cipher.fingerprint("sk-first")).thenReturn("sk-first••••xxxx");
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            service.setApiKey("openai", "sk-first", 42L, "10.0.0.1");

            ArgumentCaptor<VendorApiKeyAuditEvent> evCap =
                    ArgumentCaptor.forClass(VendorApiKeyAuditEvent.class);
            verify(auditLogger).log(evCap.capture());
            assertThat(evCap.getValue().fingerprintBefore()).isNull();
            assertThat(evCap.getValue().fingerprintAfter()).isEqualTo("sk-first••••xxxx");
        }

        @Test
        @DisplayName("clearApiKey:auditLogger.log(CLEAR),fingerprintBefore 是改前,after 是 null")
        void clearApiKey_audit() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://x", true);
            existing.setEncryptedApiKey("ct".getBytes());
            existing.setApiKeyFingerprint("sk-old••••abc");
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            service.clearApiKey("openai", 42L, "10.0.0.1");

            ArgumentCaptor<VendorApiKeyAuditEvent> evCap =
                    ArgumentCaptor.forClass(VendorApiKeyAuditEvent.class);
            verify(auditLogger, times(1)).log(evCap.capture());
            VendorApiKeyAuditEvent e = evCap.getValue();
            assertThat(e.action()).isEqualTo(VendorApiKeyAuditAction.CLEAR);
            assertThat(e.vendor()).isEqualTo("openai");
            assertThat(e.actorId()).isEqualTo(42L);
            assertThat(e.fingerprintBefore()).isEqualTo("sk-old••••abc");
            assertThat(e.fingerprintAfter()).isNull();
            assertThat(e.requestIp()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("失败路径(vendor 不存在)→ auditLogger.log 不被调(主流程失败,审计不该瞎记)")
        void failed_setApiKey_no_audit() {
            when(repo.findByVendor("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setApiKey("ghost", "sk-xxx", 1L, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class);
            verify(auditLogger, never()).log(any());
        }

        @Test
        @DisplayName("失败路径(clearApiKey vendor 不存在)→ auditLogger.log 不被调")
        void failed_clearApiKey_no_audit() {
            when(repo.findByVendor("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.clearApiKey("ghost", 1L, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class);
            verify(auditLogger, never()).log(any());
        }
    }

    // ─────────────────── getEffectiveApiKey ───────────────────

    @Nested
    @DisplayName("getEffectiveApiKey:DB 优先 + yaml 兜底")
    class GetEffectiveApiKey {

        @Test
        @DisplayName("DB 有密文 → 解密返回明文(不读 yaml)")
        void db_decrypt_wins() {
            AiVendorConfig db = newCfg(1L, "openai", "https://x", true);
            db.setEncryptedApiKey("ct".getBytes());
            when(repo.findByVendor("openai")).thenReturn(Optional.of(db));
            when(cipher.decrypt("ct".getBytes())).thenReturn("sk-decrypted-from-db");

            String result = service.getEffectiveApiKey("openai");

            assertThat(result).isEqualTo("sk-decrypted-from-db");
            verify(cipher).decrypt("ct".getBytes());
        }

        @Test
        @DisplayName("DB 无密文(null)→ yaml 兜底(读 props.getProviders().get(v).getApiKey())")
        void yaml_fallback_when_db_empty() {
            AiVendorConfig db = newCfg(1L, "ollama", "http://x", true);
            // encryptedApiKey 留 null
            when(repo.findByVendor("ollama")).thenReturn(Optional.of(db));

            String result = service.getEffectiveApiKey("ollama");

            assertThat(result).isEqualTo("sk-yaml-ollama");
            verify(cipher, never()).decrypt(any());
        }

        @Test
        @DisplayName("DB 未命中(没有该 vendor 行)→ yaml 兜底")
        void yaml_fallback_when_db_miss() {
            when(repo.findByVendor("ghost-yaml")).thenReturn(Optional.empty());

            String result = service.getEffectiveApiKey("ghost-yaml");

            assertThat(result).isEqualTo("sk-yaml-ghost");
        }

        @Test
        @DisplayName("DB + yaml 都没 → null(调用方应注入占位符)")
        void both_empty_returns_null() {
            when(repo.findByVendor("totally-ghost")).thenReturn(Optional.empty());

            String result = service.getEffectiveApiKey("totally-ghost");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("DB 密文存在但解密失败(主密钥轮换)→ 降级到 yaml,不抛错")
        void decrypt_failure_falls_back_to_yaml() {
            AiVendorConfig db = newCfg(1L, "openai", "https://x", true);
            db.setEncryptedApiKey("ct".getBytes());
            when(repo.findByVendor("openai")).thenReturn(Optional.of(db));
            when(cipher.decrypt("ct".getBytes()))
                    .thenThrow(new IllegalStateException("AEADBadTagException: GCM tag 校验失败"));

            // 不抛错 — 降级到 yaml
            String result = service.getEffectiveApiKey("openai");

            assertThat(result).isEqualTo("sk-yaml-openai");
        }

        @Test
        @DisplayName("DB 命中但 yaml 没该 vendor + 解密失败 → null(没得 fallback)")
        void decrypt_fail_no_yaml_returns_null() {
            AiVendorConfig db = newCfg(1L, "no-yaml", "https://x", true);
            db.setEncryptedApiKey("ct".getBytes());
            when(repo.findByVendor("no-yaml")).thenReturn(Optional.of(db));
            when(cipher.decrypt("ct".getBytes()))
                    .thenThrow(new IllegalStateException("AEADBadTagException"));

            // yaml 没配 no-yaml → 没得 fallback
            String result = service.getEffectiveApiKey("no-yaml");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("null / 空 vendor 返 null(不抛)")
        void blank_vendor_safe() {
            assertThat(service.getEffectiveApiKey(null)).isNull();
            assertThat(service.getEffectiveApiKey("")).isNull();
            assertThat(service.getEffectiveApiKey("   ")).isNull();
            verify(repo, never()).findByVendor(anyString());
        }
    }

    // ─────────────────── seed 适配 ───────────────────

    @Nested
    @DisplayName("seedFromYamlIfEmpty:不再 seed apiKey")
    class SeedApiKey {

        @Test
        @DisplayName("seed 时 apiKey 两列保持 null(Phase 6 显式决定)")
        void seed_does_not_populate_api_key() {
            when(repo.count()).thenReturn(0L);
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            int created = service.seedFromYamlIfEmpty();

            // 3 个 vendor 都有 base-url → 3 条 seed
            assertThat(created).isEqualTo(3);
            ArgumentCaptor<AiVendorConfig> saved = ArgumentCaptor.forClass(AiVendorConfig.class);
            verify(repo, times(3)).save(saved.capture());
            // 关键断言:seed 出来的实体 apiKey 两列都是 null(不管 yaml api-key 有没有)
            for (AiVendorConfig cfg : saved.getAllValues()) {
                assertThat(cfg.getEncryptedApiKey())
                        .as("seed 不写密文,Phase 6 显式决定:admin 走 PUT 端点 (vendor=" + cfg.getVendor() + ")")
                        .isNull();
                assertThat(cfg.getApiKeyFingerprint())
                        .as("seed 不写 fingerprint,Phase 6 显式决定:admin 走 PUT 端点 (vendor=" + cfg.getVendor() + ")")
                        .isNull();
            }
            // seed 路径完全不调 cipher
            verify(cipher, never()).encrypt(anyString());
            verify(cipher, never()).fingerprint(anyString());
        }
    }

    // ─────────────────── helpers ───────────────────

    private static AiProperties.Provider providerWithKey(boolean enabled, String baseUrl, String apiKey) {
        AiProperties.Provider p = new AiProperties.Provider();
        p.setEnabled(enabled);
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        p.setDefaultModel("default-model-" + System.nanoTime());   // 避免 null
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
