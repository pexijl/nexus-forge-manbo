package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.FallbackChainDto;
import com.nexusforge.ai.service.FallbackChainService;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainSource;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainView;
import com.nexusforge.base.Result;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 7 — {@link AiAdminFallbackChainController} 单元测试。
 *
 * <p>测试范围:
 * <ul>
 *   <li>GET:接 service.findEffective,透出 source + vendors + updatedAt</li>
 *   <li>PUT:接 DTO 调 service.replace;DTO 透传给 service,VO 反映新值</li>
 *   <li>DELETE:调 service.reset,返 yaml 兜底视图</li>
 *   <li>service 抛 BusinessException 时透传(状态码 / 错误信息由 GlobalExceptionHandler 处理)</li>
 * </ul>
 *
 * <p>不重复测试鉴权({@code @PreAuthorize})和路由注册(Spring Web 层职责),
 * 这里只验 service 调用 + VO 转换。
 */
@DisplayName("AiAdminFallbackChainController — Phase 7 降级链端点")
class AiAdminFallbackChainControllerTest {

    FallbackChainService service;
    AiAdminFallbackChainController controller;

    @BeforeEach
    void setUp() {
        service = mock(FallbackChainService.class);
        controller = new AiAdminFallbackChainController(service);
    }

    // ─────────────────── GET ───────────────────

    @Nested
    @DisplayName("GET /api/admin/ai/fallback-chain")
    class GetEndpoint {

        @Test
        @DisplayName("DB 命中:source=DB,vendors 跟 DB 一致,updatedAt 非空")
        void db_source() {
            FallbackChainView view = new FallbackChainView(
                    List.of("ollama", "deepseek"),
                    FallbackChainSource.DB,
                    OffsetDateTime.now());
            when(service.findEffective()).thenReturn(view);

            Result<com.nexusforge.ai.controller.vo.FallbackChainVo> resp = controller.get();

            assertThat(resp.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(resp.getData().getSource()).isEqualTo(FallbackChainSource.DB);
            assertThat(resp.getData().getVendors()).containsExactly("ollama", "deepseek");
            assertThat(resp.getData().getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("YAML 兜底:source=YAML_FALLBACK,updatedAt 为 null(VO 字段被 @JsonInclude(NON_NULL) 隐藏)")
        void yaml_fallback_source() {
            FallbackChainView view = new FallbackChainView(
                    List.of("ollama"),
                    FallbackChainSource.YAML_FALLBACK,
                    null);
            when(service.findEffective()).thenReturn(view);

            Result<com.nexusforge.ai.controller.vo.FallbackChainVo> resp = controller.get();

            assertThat(resp.getData().getSource()).isEqualTo(FallbackChainSource.YAML_FALLBACK);
            assertThat(resp.getData().getVendors()).containsExactly("ollama");
        }

        @Test
        @DisplayName("EMPTY 兜底:source=EMPTY,vendors 空")
        void empty_source() {
            FallbackChainView view = new FallbackChainView(
                    List.of(),
                    FallbackChainSource.EMPTY,
                    null);
            when(service.findEffective()).thenReturn(view);

            Result<com.nexusforge.ai.controller.vo.FallbackChainVo> resp = controller.get();

            assertThat(resp.getData().getSource()).isEqualTo(FallbackChainSource.EMPTY);
            assertThat(resp.getData().getVendors()).isEmpty();
        }
    }

    // ─────────────────── PUT ───────────────────

    @Nested
    @DisplayName("PUT /api/admin/ai/fallback-chain")
    class PutEndpoint {

        @Test
        @DisplayName("happy path:DTO 透传给 service.replace,返 VO 反映新值")
        void happy_path() {
            FallbackChainDto dto = new FallbackChainDto();
            dto.setVendors(List.of("ollama", "openai"));

            FallbackChainView view = new FallbackChainView(
                    List.of("ollama", "openai"),
                    FallbackChainSource.DB,
                    OffsetDateTime.now());
            when(service.replace(eq(List.of("ollama", "openai")))).thenReturn(view);

            Result<com.nexusforge.ai.controller.vo.FallbackChainVo> resp = controller.replace(dto);

            assertThat(resp.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(resp.getData().getSource()).isEqualTo(FallbackChainSource.DB);
            assertThat(resp.getData().getVendors()).containsExactly("ollama", "openai");
            verify(service).replace(List.of("ollama", "openai"));
        }

        @Test
        @DisplayName("空 list 也合法:service 收到空 list,VO 反映 source=DB,vendors=[]")
        void empty_list_legal() {
            FallbackChainDto dto = new FallbackChainDto();
            dto.setVendors(List.of());

            FallbackChainView view = new FallbackChainView(
                    List.of(),
                    FallbackChainSource.DB,
                    OffsetDateTime.now());
            when(service.replace(eq(List.of()))).thenReturn(view);

            Result<com.nexusforge.ai.controller.vo.FallbackChainVo> resp = controller.replace(dto);

            assertThat(resp.getData().getSource()).isEqualTo(FallbackChainSource.DB);
            assertThat(resp.getData().getVendors()).isEmpty();
        }

        @Test
        @DisplayName("service 抛 BusinessException(vendor 不存在)→ controller 透传")
        void unknown_vendor_throws() {
            FallbackChainDto dto = new FallbackChainDto();
            dto.setVendors(List.of("ghost-vendor"));
            when(service.replace(anyList()))
                    .thenThrow(new BusinessException(ResultCode.LLM_INVALID_REQUEST,
                            "vendor=ghost-vendor 不在 spring.ai.providers.* 中"));

            assertThatThrownBy(() -> controller.replace(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
        }
    }

    // ─────────────────── DELETE ───────────────────

    @Nested
    @DisplayName("DELETE /api/admin/ai/fallback-chain")
    class DeleteEndpoint {

        @Test
        @DisplayName("happy:service.reset,返 yaml 兜底视图")
        void happy_path() {
            FallbackChainView view = new FallbackChainView(
                    List.of("ollama"),
                    FallbackChainSource.YAML_FALLBACK,
                    null);
            when(service.reset()).thenReturn(view);

            Result<com.nexusforge.ai.controller.vo.FallbackChainVo> resp = controller.reset();

            assertThat(resp.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(resp.getData().getSource()).isEqualTo(FallbackChainSource.YAML_FALLBACK);
            assertThat(resp.getData().getVendors()).containsExactly("ollama");
            verify(service).reset();
        }

        @Test
        @DisplayName("幂等:DB 本来就没行 → service.reset 仍返 OK(yaml 兜底)")
        void idempotent_no_row() {
            FallbackChainView view = new FallbackChainView(
                    List.of(),
                    FallbackChainSource.EMPTY,
                    null);
            when(service.reset()).thenReturn(view);

            Result<com.nexusforge.ai.controller.vo.FallbackChainVo> resp = controller.reset();

            assertThat(resp.getData().getSource()).isEqualTo(FallbackChainSource.EMPTY);
            assertThat(resp.getData().getVendors()).isEmpty();
        }
    }
}
