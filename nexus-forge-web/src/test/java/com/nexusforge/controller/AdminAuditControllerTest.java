package com.nexusforge.controller;

import com.nexusforge.audit.OperationAuditLog;
import com.nexusforge.audit.OperationAuditLogRepository;
import com.nexusforge.audit.OperationAuditLogVo;
import com.nexusforge.base.PageResult;
import com.nexusforge.base.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Audit Commit 4 单测 —— {@link AdminAuditController}。
 *
 * <p>Mockito 隔离 {@link OperationAuditLogRepository},验证:
 * <ul>
 *   <li>多维过滤参数透传给 repo.adminSearch</li>
 *   <li>page 1-based → 0-based 转换 + 默认按 createdAt desc</li>
 *   <li>VO 映射(不暴露完整 IP / 不暴露 metadata)</li>
 *   <li>空过滤(null 维度)也走 repo</li>
 * </ul>
 *
 * <p>{@code @PreAuthorize} 真实鉴权由 commit 5 IT + Spring Security 验证,
 * 本单测只验证 controller 业务逻辑。</p>
 */
class AdminAuditControllerTest {

    private OperationAuditLogRepository repo;
    private AdminAuditController controller;

    @BeforeEach
    void setUp() {
        repo = mock(OperationAuditLogRepository.class);
        controller = new AdminAuditController(repo);
    }

    private OperationAuditLog stubLog(Long id, Long userId, String action,
                                      String resource, String ip) {
        OperationAuditLog log = new OperationAuditLog();
        log.setId(id);
        log.setUserId(userId);
        log.setAction(action);
        log.setResource(resource);
        log.setResourceId("100");
        log.setMethod("PUT");
        log.setPath("/api/test");
        log.setIp(ip);
        log.setUserAgent("Mozilla/5.0");
        log.setResult(OperationAuditLog.AuditResult.SUCCESS);
        log.setStatusCode(200);
        log.setLatencyMs(50L);
        // BaseEntity 字段
        log.setCreatedAt(OffsetDateTime.now());
        return log;
    }

    // ─────────────────────────────────────────────
    //  Forwarding
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Forwarding")
    class Forwarding {

        @Test
        @DisplayName("全参数 → repo.adminSearch(userId, action, resource, pageable)")
        void full_params_forwarded() {
            Page<OperationAuditLog> page = new PageImpl<>(List.of(),
                    PageRequest.of(0, 20), 0);
            when(repo.adminSearch(eq(100L), eq("user.update"), eq("user"), any(Pageable.class)))
                    .thenReturn(page);

            Result<PageResult<OperationAuditLogVo>> result = controller.search(
                    100L, "user.update", "user", 1, 20);

            assertThat(result.getData().getTotal()).isZero();
            // 验证 captor:page 1-based → 0-based,sort createdAt desc
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(repo, times(1)).adminSearch(eq(100L), eq("user.update"), eq("user"),
                    captor.capture());
            Pageable pageable = captor.getValue();
            assertThat(pageable.getPageNumber()).isZero();  // 1-based → 0-based
            assertThat(pageable.getPageSize()).isEqualTo(20);
            assertThat(pageable.getSort().getOrderFor("createdAt").getDirection().toString())
                    .isEqualTo("DESC");
        }

        @Test
        @DisplayName("全 null 过滤 → repo 也接受(null 维度跳过)")
        void all_null_filters() {
            Page<OperationAuditLog> page = new PageImpl<>(List.of(),
                    PageRequest.of(0, 20), 0);
            when(repo.adminSearch(eq(null), eq(null), eq(null), any(Pageable.class)))
                    .thenReturn(page);

            Result<PageResult<OperationAuditLogVo>> result = controller.search(
                    null, null, null, 1, 20);

            assertThat(result.getCode()).isEqualTo(com.nexusforge.enums.ResultCode.SUCCESS.getCode());
            verify(repo, times(1)).adminSearch(eq(null), eq(null), eq(null), any(Pageable.class));
        }

        @Test
        @DisplayName("page=0 → 0-based 转成 0(Math.max 兜底)")
        void page_zero_max_clamp() {
            Page<OperationAuditLog> page = new PageImpl<>(List.of(),
                    PageRequest.of(0, 20), 0);
            when(repo.adminSearch(any(), any(), any(), any(Pageable.class))).thenReturn(page);

            controller.search(null, null, null, 0, 20);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(repo).adminSearch(any(), any(), any(), captor.capture());
            assertThat(captor.getValue().getPageNumber()).isZero();
        }
    }

    // ─────────────────────────────────────────────
    //  VO mapping
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("VOMapping")
    class VOMapping {

        @Test
        @DisplayName("IP > 16 字符截断,完整 IP 不暴露")
        void ip_truncated_in_vo() {
            OperationAuditLog log = stubLog(1L, 100L, "user.update", "user",
                    "192.168.123.456.789.extra");  // 27 chars
            Page<OperationAuditLog> page = new PageImpl<>(List.of(log),
                    PageRequest.of(0, 20), 1);
            when(repo.adminSearch(any(), any(), any(), any(Pageable.class))).thenReturn(page);

            Result<PageResult<OperationAuditLogVo>> result = controller.search(
                    null, null, null, 1, 20);

            OperationAuditLogVo vo = result.getData().getRecords().get(0);
            // VO ipPrefix 截断:16 字符 + "…"
            assertThat(vo.ipPrefix()).hasSizeLessThanOrEqualTo(17);
            assertThat(vo.ipPrefix()).endsWith("…");
            // VO 不暴露 metadata / bucket
            // (record 没有 metadata 字段,验证 VO 类型本身)
            assertThat(vo).isInstanceOf(OperationAuditLogVo.class);
        }

        @Test
        @DisplayName("VO 字段与 entity 字段映射对齐")
        void vo_field_mapping() {
            OperationAuditLog log = stubLog(7L, 100L, "user.update", "user", "127.0.0.1");
            Page<OperationAuditLog> page = new PageImpl<>(List.of(log),
                    PageRequest.of(0, 20), 1);
            when(repo.adminSearch(any(), any(), any(), any(Pageable.class))).thenReturn(page);

            Result<PageResult<OperationAuditLogVo>> result = controller.search(
                    null, null, null, 1, 20);

            OperationAuditLogVo vo = result.getData().getRecords().get(0);
            assertThat(vo.id()).isEqualTo(7L);
            assertThat(vo.userId()).isEqualTo(100L);
            assertThat(vo.action()).isEqualTo("user.update");
            assertThat(vo.resource()).isEqualTo("user");
            assertThat(vo.result()).isEqualTo("SUCCESS");
            assertThat(vo.statusCode()).isEqualTo(200);
            assertThat(vo.latencyMs()).isEqualTo(50L);
        }

        @Test
        @DisplayName("空列表 → 空 VO 列表(不 NPE)")
        void empty_list_safe() {
            Page<OperationAuditLog> page = new PageImpl<>(List.of(),
                    PageRequest.of(0, 20), 0);
            when(repo.adminSearch(any(), any(), any(), any(Pageable.class))).thenReturn(page);

            Result<PageResult<OperationAuditLogVo>> result = controller.search(
                    null, null, null, 1, 20);

            assertThat(result.getData().getRecords()).isEmpty();
        }
    }
}
