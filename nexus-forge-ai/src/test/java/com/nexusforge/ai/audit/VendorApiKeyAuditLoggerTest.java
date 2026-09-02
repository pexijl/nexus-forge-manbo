package com.nexusforge.ai.audit;

import com.nexusforge.ai.entity.AiApiKeyAuditLog;
import com.nexusforge.ai.enums.VendorApiKeyAuditAction;
import com.nexusforge.ai.repository.AiApiKeyAuditLogRepository;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 8 — {@link VendorApiKeyAuditLogger} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>happy:SET 事件 → row 字段正确 + metadata 包含 vendor / fingerprint_before / fingerprint_after / request_ip</li>
 *   <li>happy:CLEAR 事件 → 同上</li>
 *   <li>参数校验:null action / null vendor → log error 不写库(不抛错)</li>
 *   <li>容错:repo.save 抛错 → log warn 不抛错(主业务不阻塞)</li>
 *   <li>actorRole null/blank → 兜底 "SYSTEM"</li>
 *   <li>requestIp null → metadata 存 null(不存空串)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VendorApiKeyAuditLogger — Phase 8 apiKey 轮换审计")
class VendorApiKeyAuditLoggerTest {

    @Mock AiApiKeyAuditLogRepository repo;

    private VendorApiKeyAuditLogger logger;

    @BeforeEach
    void setUp() {
        logger = new VendorApiKeyAuditLogger(repo);
        when(repo.save(any(AiApiKeyAuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private VendorApiKeyAuditEvent setEvent(String vendor, Long actorId, String ip,
                                            String fpBefore, String fpAfter) {
        return new VendorApiKeyAuditEvent(
                VendorApiKeyAuditAction.SET,
                vendor,
                actorId,
                "ADMIN",
                null,
                fpBefore,
                fpAfter,
                ip);
    }

    private VendorApiKeyAuditEvent clearEvent(String vendor, Long actorId, String ip, String fpBefore) {
        return new VendorApiKeyAuditEvent(
                VendorApiKeyAuditAction.CLEAR,
                vendor,
                actorId,
                "ADMIN",
                null,
                fpBefore,
                null,
                ip);
    }

    // ─────────────────── happy path ───────────────────

    @Nested
    @DisplayName("happy:SET 事件写入正确")
    class SetEvent {

        @Test
        @DisplayName("actorId / vendor / fingerprint / ip / actorRole 全部透传到 row")
        void set_full_event() {
            logger.log(setEvent("openai", 42L, "10.0.0.1",
                    "sk-old••••prev", "sk-new••••curr"));

            ArgumentCaptor<AiApiKeyAuditLog> rowCap = ArgumentCaptor.forClass(AiApiKeyAuditLog.class);
            verify(repo).save(rowCap.capture());
            AiApiKeyAuditLog row = rowCap.getValue();
            assertThat(row.getAction()).isEqualTo(VendorApiKeyAuditAction.SET);
            assertThat(row.getActorId()).isEqualTo(42L);
            assertThat(row.getActorRole()).isEqualTo("ADMIN");
            assertThat(row.getReason()).isNull();

            Map<String, Object> m = row.getMetadata();
            assertThat(m).isNotNull();
            assertThat(m.get("vendor")).isEqualTo("openai");
            assertThat(m.get("fingerprint_before")).isEqualTo("sk-old••••prev");
            assertThat(m.get("fingerprint_after")).isEqualTo("sk-new••••curr");
            assertThat(m.get("request_ip")).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("fingerprintBefore=null(首次装机)→ 也能正确存(运营可推断 '新装' vs '轮换')")
        void set_first_install() {
            logger.log(setEvent("openai", 42L, "10.0.0.1", null, "sk-new••••xxxx"));

            ArgumentCaptor<AiApiKeyAuditLog> rowCap = ArgumentCaptor.forClass(AiApiKeyAuditLog.class);
            verify(repo).save(rowCap.capture());
            Map<String, Object> m = rowCap.getValue().getMetadata();
            assertThat(m.get("fingerprint_before")).isNull();
            assertThat(m.get("fingerprint_after")).isEqualTo("sk-new••••xxxx");
        }
    }

    @Nested
    @DisplayName("happy:CLEAR 事件写入正确")
    class ClearEvent {

        @Test
        @DisplayName("fingerprintAfter=null(CLEAR 总是),fingerprintBefore 是改前")
        void clear_event() {
            logger.log(clearEvent("openai", 42L, "10.0.0.1", "sk-old••••abc"));

            ArgumentCaptor<AiApiKeyAuditLog> rowCap = ArgumentCaptor.forClass(AiApiKeyAuditLog.class);
            verify(repo).save(rowCap.capture());
            AiApiKeyAuditLog row = rowCap.getValue();
            assertThat(row.getAction()).isEqualTo(VendorApiKeyAuditAction.CLEAR);
            Map<String, Object> m = row.getMetadata();
            assertThat(m.get("vendor")).isEqualTo("openai");
            assertThat(m.get("fingerprint_before")).isEqualTo("sk-old••••abc");
            assertThat(m.get("fingerprint_after")).isNull();
            assertThat(m.get("request_ip")).isEqualTo("10.0.0.1");
        }
    }

    // ─────────────────── 参数校验 ───────────────────

    @Nested
    @DisplayName("参数校验:缺关键字段 → log error 不写库(不抛错)")
    class ArgumentValidation {

        @Test
        @DisplayName("null event → 不调 repo.save,return")
        void null_event() {
            logger.log(null);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("null action → 不调 repo.save")
        void null_action() {
            VendorApiKeyAuditEvent bad = new VendorApiKeyAuditEvent(
                    null, "openai", 1L, "ADMIN", null, null, null, "127.0.0.1");
            logger.log(bad);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("null vendor → 不调 repo.save")
        void null_vendor() {
            VendorApiKeyAuditEvent bad = new VendorApiKeyAuditEvent(
                    VendorApiKeyAuditAction.SET, null, 1L, "ADMIN", null, null, null, "127.0.0.1");
            logger.log(bad);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("blank vendor → 不调 repo.save")
        void blank_vendor() {
            VendorApiKeyAuditEvent bad = new VendorApiKeyAuditEvent(
                    VendorApiKeyAuditAction.SET, "  ", 1L, "ADMIN", null, null, null, "127.0.0.1");
            logger.log(bad);
            verify(repo, never()).save(any());
        }
    }

    // ─────────────────── 容错 ───────────────────

    @Nested
    @DisplayName("容错:repo.save 抛错 → log warn 不抛错")
    class FaultTolerance {

        @Test
        @DisplayName("DB 故障 → logger 不抛错(主业务不受影响)")
        void repo_throws() {
            doThrow(new RuntimeException("DB 挂了")).when(repo).save(any(AiApiKeyAuditLog.class));

            // 不抛错
            logger.log(setEvent("openai", 42L, "10.0.0.1", "fp-b", "fp-a"));
        }

        @Test
        @DisplayName("actorRole null → 兜底 'SYSTEM'")
        void actor_role_null_fallback() {
            VendorApiKeyAuditEvent ev = new VendorApiKeyAuditEvent(
                    VendorApiKeyAuditAction.SET, "openai", 1L, null, null, null, "fp-a", "127.0.0.1");
            logger.log(ev);

            ArgumentCaptor<AiApiKeyAuditLog> rowCap = ArgumentCaptor.forClass(AiApiKeyAuditLog.class);
            verify(repo).save(rowCap.capture());
            assertThat(rowCap.getValue().getActorRole()).isEqualTo("SYSTEM");
        }

        @Test
        @DisplayName("actorRole blank → 兜底 'SYSTEM'")
        void actor_role_blank_fallback() {
            VendorApiKeyAuditEvent ev = new VendorApiKeyAuditEvent(
                    VendorApiKeyAuditAction.SET, "openai", 1L, "  ", null, null, "fp-a", "127.0.0.1");
            logger.log(ev);

            ArgumentCaptor<AiApiKeyAuditLog> rowCap = ArgumentCaptor.forClass(AiApiKeyAuditLog.class);
            verify(repo).save(rowCap.capture());
            assertThat(rowCap.getValue().getActorRole()).isEqualTo("SYSTEM");
        }
    }

    // ─────────────────── metadata 字段完整性 ───────────────────

    @Nested
    @DisplayName("metadata:4 个 key 都有(指纹 / vendor / ip),null 存 null 不存空串")
    class MetadataKeys {

        @Test
        @DisplayName("全 null 也能正常写 — 4 个 key 都在(指纹 null 代表'首次装' / CLEAR 等)")
        void all_null_fingerprints() {
            VendorApiKeyAuditEvent ev = new VendorApiKeyAuditEvent(
                    VendorApiKeyAuditAction.SET, "openai", 1L, "ADMIN", null, null, null, null);
            logger.log(ev);

            ArgumentCaptor<AiApiKeyAuditLog> rowCap = ArgumentCaptor.forClass(AiApiKeyAuditLog.class);
            verify(repo).save(rowCap.capture());
            Map<String, Object> m = rowCap.getValue().getMetadata();
            // 4 个 key 都在(包括 fingerprint_before=null / after=null / request_ip=null)
            assertThat(m).containsOnlyKeys("vendor", "fingerprint_before", "fingerprint_after", "request_ip");
        }
    }
}
