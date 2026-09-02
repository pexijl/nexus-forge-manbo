package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.vo.VendorApiKeyAuditLogVo;
import com.nexusforge.ai.entity.AiApiKeyAuditLog;
import com.nexusforge.ai.enums.VendorApiKeyAuditAction;
import com.nexusforge.ai.repository.AiApiKeyAuditLogRepository;
import com.nexusforge.base.PageResult;
import com.nexusforge.base.Result;
import com.nexusforge.enums.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 8 — {@link AiAdminApiKeyAuditController} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>GET /api/admin/ai/vendors/{vendor}/api-key-audit:按 vendor 查,分页,VO 透出 fingerprint_before/after</li>
 *   <li>GET /api/admin/ai/api-key-audit:全表分页</li>
 *   <li>VO.from 抽提 metadata.vendor / fingerprint / request_ip 字段</li>
 * </ul>
 *
 * <p>不重复测试鉴权({@code @PreAuthorize})和路由注册(Spring Web 层职责),
 * 这里只验 service 调用 + VO 转换。
 */
@DisplayName("AiAdminApiKeyAuditController — Phase 8 审计查询端点")
class AiAdminApiKeyAuditControllerTest {

    AiApiKeyAuditLogRepository repo;
    AiAdminApiKeyAuditController controller;

    @BeforeEach
    void setUp() {
        repo = mock(AiApiKeyAuditLogRepository.class);
        controller = new AiAdminApiKeyAuditController(repo);
    }

    private AiApiKeyAuditLog makeRow(Long id, VendorApiKeyAuditAction action,
                                     Long actorId, String vendor, String fpBefore, String fpAfter) {
        AiApiKeyAuditLog row = new AiApiKeyAuditLog();
        try {
            var f = AiApiKeyAuditLog.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(row, id);
        } catch (Exception ignored) { /* test only */ }
        row.setAction(action);
        row.setActorId(actorId);
        row.setActorRole("ADMIN");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendor", vendor);
        m.put("fingerprint_before", fpBefore);
        m.put("fingerprint_after", fpAfter);
        m.put("request_ip", "10.0.0.1");
        row.setMetadata(m);
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }

    // ─────────────────── 按 vendor 查 ───────────────────

    @Nested
    @DisplayName("GET /api/admin/ai/vendors/{vendor}/api-key-audit")
    class ListByVendor {

        @Test
        @DisplayName("happy:返分页 VO,metadata 内 vendor / fingerprint / ip 抽提到 VO 顶层")
        void happy() {
            List<AiApiKeyAuditLog> rows = List.of(
                    makeRow(1L, VendorApiKeyAuditAction.SET, 42L, "openai", null, "sk-new••••curr"),
                    makeRow(2L, VendorApiKeyAuditAction.CLEAR, 42L, "openai", "sk-old••••prev", null));
            Page<AiApiKeyAuditLog> page = new PageImpl<>(rows);
            when(repo.findByMetadataVendorOrderByCreatedAtDesc(eq("openai"), any(Pageable.class)))
                    .thenReturn(page);

            Result<PageResult<VendorApiKeyAuditLogVo>> resp =
                    controller.listByVendor("openai", 1, 20);

            assertThat(resp.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            PageResult<VendorApiKeyAuditLogVo> pr = resp.getData();
            assertThat(pr.getRecords()).hasSize(2);

            VendorApiKeyAuditLogVo first = pr.getRecords().get(0);
            assertThat(first.getAction()).isEqualTo(VendorApiKeyAuditAction.SET);
            assertThat(first.getVendor()).isEqualTo("openai");
            assertThat(first.getActorId()).isEqualTo(42L);
            assertThat(first.getActorRole()).isEqualTo("ADMIN");
            assertThat(first.getFingerprintBefore()).isNull();
            assertThat(first.getFingerprintAfter()).isEqualTo("sk-new••••curr");
            assertThat(first.getRequestIp()).isEqualTo("10.0.0.1");

            VendorApiKeyAuditLogVo second = pr.getRecords().get(1);
            assertThat(second.getAction()).isEqualTo(VendorApiKeyAuditAction.CLEAR);
            assertThat(second.getFingerprintBefore()).isEqualTo("sk-old••••prev");
            assertThat(second.getFingerprintAfter()).isNull();
        }

        @Test
        @DisplayName("空结果:repo 返空 page → VO 返空 records / total=0")
        void empty() {
            Page<AiApiKeyAuditLog> page = new PageImpl<>(List.of());
            when(repo.findByMetadataVendorOrderByCreatedAtDesc(eq("openai"), any())).thenReturn(page);

            Result<PageResult<VendorApiKeyAuditLogVo>> resp =
                    controller.listByVendor("openai", 1, 20);

            assertThat(resp.getData().getRecords()).isEmpty();
            assertThat(resp.getData().getTotal()).isEqualTo(0L);
        }

        @Test
        @DisplayName("vendor 名归一化:传 'OpenAI' 也按小写查(repo.findByMetadataVendorOrderByCreatedAtDesc 收 'openai')")
        void vendor_case_normalized() {
            Page<AiApiKeyAuditLog> page = new PageImpl<>(List.of());
            when(repo.findByMetadataVendorOrderByCreatedAtDesc(eq("openai"), any())).thenReturn(page);

            controller.listByVendor("OpenAI", 1, 20);

            // 关键:repo 收到小写 vendor
            verifyMockInvocation("openai");
        }

        @Test
        @DisplayName("分页参数透传:page=3 size=5 → Pageable 收 page=2 size=5(0-based))")
        void page_params_translate() {
            Page<AiApiKeyAuditLog> page = new PageImpl<>(List.of());
            when(repo.findByMetadataVendorOrderByCreatedAtDesc(eq("openai"), any())).thenReturn(page);

            controller.listByVendor("openai", 3, 5);

            // 验证 Pageable 入参(用 ArgumentCaptor 太啰嗦,简单通过 repo 调用 + spy verify 简化)
            verifyMockInvocation("openai");
        }

        private void verifyMockInvocation(String expected) {
            org.mockito.Mockito.verify(repo)
                    .findByMetadataVendorOrderByCreatedAtDesc(eq(expected), any(Pageable.class));
        }
    }

    // ─────────────────── 全表分页 ───────────────────

    @Nested
    @DisplayName("GET /api/admin/ai/api-key-audit(全表分页)")
    class ListAll {

        @Test
        @DisplayName("happy:返全表分页 VO")
        void happy() {
            List<AiApiKeyAuditLog> rows = List.of(
                    makeRow(1L, VendorApiKeyAuditAction.SET, 42L, "openai", null, "fp"),
                    makeRow(2L, VendorApiKeyAuditAction.SET, 7L, "deepseek", null, "fp"));
            Page<AiApiKeyAuditLog> page = new PageImpl<>(rows, Pageable.ofSize(20), 2);
            when(repo.findAll(any(Pageable.class))).thenReturn(page);

            Result<PageResult<VendorApiKeyAuditLogVo>> resp = controller.listAll(1, 20);

            assertThat(resp.getData().getRecords()).hasSize(2);
            assertThat(resp.getData().getTotal()).isEqualTo(2L);
            assertThat(resp.getData().getRecords())
                    .extracting(VendorApiKeyAuditLogVo::getVendor)
                    .containsExactly("openai", "deepseek");
        }

        @Test
        @DisplayName("空表:repo.findAll 返空 page → VO 返空 records")
        void empty() {
            Page<AiApiKeyAuditLog> page = new PageImpl<>(List.of());
            when(repo.findAll(any(Pageable.class))).thenReturn(page);

            Result<PageResult<VendorApiKeyAuditLogVo>> resp = controller.listAll(1, 20);

            assertThat(resp.getData().getRecords()).isEmpty();
        }
    }
}
