package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.VendorApiKeyDto;
import com.nexusforge.ai.controller.vo.VendorApiKeyVo;
import com.nexusforge.ai.entity.AiVendorConfig;
import com.nexusforge.ai.service.VendorConfigService;
import com.nexusforge.base.Result;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 6 — {@link AiAdminVendorController} 的 apiKey 端点单元测试
 * (Phase 8 起 controller 加 HttpServletRequest + actorId 透传给 service,审计用)。
 *
 * <p>测试范围:
 * <ul>
 *   <li>PUT /api/admin/ai/vendors/{vendor}/api-key:接 DTO 调 service.setApiKey,
 *       透传 actorId(从 SecurityContext)+ requestIp(从 HttpServletRequest)</li>
 *   <li>DELETE /api/admin/ai/vendors/{vendor}/api-key:调 service.clearApiKey + 同样透传</li>
 *   <li>返回 VO 透出 fingerprint + updatedAt,绝不暴露密文</li>
 *   <li>service 抛 BusinessException 时透传(状态码 / 错误信息由 GlobalExceptionHandler 处理)</li>
 * </ul>
 *
 * <p>不重复测试鉴权({@code @PreAuthorize})和路由注册(Spring Web 层职责),
 * 这里只验 service 调用 + VO 转换。集成 / WebMvc 测试另起文件覆盖。
 */
@DisplayName("AiAdminVendorController — Phase 6 apiKey 端点 + Phase 8 审计透传")
class AiAdminVendorApiKeyControllerTest {

    VendorConfigService service;
    HttpServletRequest request;
    AiAdminVendorController controller;

    @BeforeEach
    void setUp() {
        service = mock(VendorConfigService.class);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        controller = new AiAdminVendorController(service);
    }

    // ─────────────────── PUT /{vendor}/api-key ───────────────────

    @Nested
    @DisplayName("PUT /api/admin/ai/vendors/{vendor}/api-key")
    class SetApiKeyEndpoint {

        @Test
        @DisplayName("happy path:接明文 → service.setApiKey(vendor, plaintext, actorId, requestIp) → 返回 VO(有 fingerprint)")
        void happy_path() {
            AiVendorConfig saved = newCfg(1L, "openai", "https://x", true);
            saved.setEncryptedApiKey("ct".getBytes());
            saved.setApiKeyFingerprint("sk-n••••abcd1234");
            saved.setUpdatedAt(OffsetDateTime.now());
            when(service.setApiKey(eq("openai"), eq("sk-new-prod"), any(), anyString())).thenReturn(saved);

            VendorApiKeyDto dto = new VendorApiKeyDto();
            dto.setApiKey("sk-new-prod");

            Result<VendorApiKeyVo> result = controller.setApiKey("openai", dto, request);

            assertThat(result).isNotNull();
            assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().getVendor()).isEqualTo("openai");
            assertThat(result.getData().getHasApiKey()).isTrue();
            assertThat(result.getData().getApiKeyFingerprint()).isEqualTo("sk-n••••abcd1234");
            assertThat(result.getData().getUpdatedAt()).isNotNull();
            // 关键:VO 不透密文
            assertThat(result.getData().toString())
                    .as("VO 序列化不应包含密文")
                    .doesNotContain("ct")
                    .doesNotContain("encrypted");

            // 关键:service 收到 4 参(vendor / plaintext / actorId / ip)
            ArgumentCaptor<String> vendorCap = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> apiKeyCap = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> ipCap = ArgumentCaptor.forClass(String.class);
            verify(service).setApiKey(vendorCap.capture(), apiKeyCap.capture(), any(), ipCap.capture());
            assertThat(vendorCap.getValue()).isEqualTo("openai");
            assertThat(apiKeyCap.getValue()).isEqualTo("sk-new-prod");
            assertThat(ipCap.getValue()).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("vendor 大小写归一化:path 是 'OpenAI' 传给 service 时变 'openai'(service 内部归一化)")
        void path_case_passthrough() {
            // controller 不做归一化,直接透传 vendor 给 service(service 自己处理)
            AiVendorConfig saved = newCfg(1L, "openai", "https://x", true);
            saved.setApiKeyFingerprint("sk-n••••1234");
            when(service.setApiKey(eq("OpenAI"), anyString(), any(), anyString())).thenReturn(saved);

            VendorApiKeyDto dto = new VendorApiKeyDto();
            dto.setApiKey("sk-x");
            controller.setApiKey("OpenAI", dto, request);

            verify(service).setApiKey("OpenAI", "sk-x", null, "127.0.0.1");   // 透传,归一化在 service
        }

        @Test
        @DisplayName("service 抛 BusinessException(vendor 不存在)→ 透传")
        void service_throws_propagates() {
            when(service.setApiKey(eq("ghost"), eq("sk-x"), any(), anyString()))
                    .thenThrow(new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                            "vendor=ghost 在 ai_vendor_config 不存在"));

            VendorApiKeyDto dto = new VendorApiKeyDto();
            dto.setApiKey("sk-x");

            assertThatThrownBy(() -> controller.setApiKey("ghost", dto, request))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("空字符串 apiKey:service 抛 LLM_INVALID_REQUEST → 透传")
        void empty_apikey_propagates() {
            when(service.setApiKey(eq("openai"), eq(""), any(), anyString()))
                    .thenThrow(new BusinessException(ResultCode.LLM_INVALID_REQUEST, "apiKey 不能为空字符串"));

            VendorApiKeyDto dto = new VendorApiKeyDto();
            dto.setApiKey("");

            assertThatThrownBy(() -> controller.setApiKey("openai", dto, request))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("request 返 null IP:controller 透传 null(不抛错)— 实际部署在反代后面场景)")
        void null_ip_passthrough() {
            when(request.getRemoteAddr()).thenReturn(null);
            AiVendorConfig saved = newCfg(1L, "openai", "https://x", true);
            saved.setApiKeyFingerprint("fp");
            when(service.setApiKey(anyString(), anyString(), any(), any())).thenReturn(saved);

            VendorApiKeyDto dto = new VendorApiKeyDto();
            dto.setApiKey("sk-x");
            controller.setApiKey("openai", dto, request);

            // service 收到 IP=null
            verify(service).setApiKey("openai", "sk-x", null, null);
        }
    }

    // ─────────────────── DELETE /{vendor}/api-key ───────────────────

    @Nested
    @DisplayName("DELETE /api/admin/ai/vendors/{vendor}/api-key")
    class ClearApiKeyEndpoint {

        @Test
        @DisplayName("happy path:service.clearApiKey(vendor, actorId, requestIp) → 返回 VO(hasApiKey=false)")
        void happy_path() {
            AiVendorConfig cleared = newCfg(1L, "openai", "https://x", true);
            // 清空后两列为 null
            cleared.setEncryptedApiKey(null);
            cleared.setApiKeyFingerprint(null);
            cleared.setUpdatedAt(OffsetDateTime.now());
            when(service.clearApiKey("openai", null, "127.0.0.1")).thenReturn(cleared);

            Result<VendorApiKeyVo> result = controller.clearApiKey("openai", request);

            assertThat(result).isNotNull();
            assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(result.getData().getVendor()).isEqualTo("openai");
            assertThat(result.getData().getHasApiKey()).isFalse();
            assertThat(result.getData().getApiKeyFingerprint())
                    .as("清空后 fingerprint 应为 null,从不显示历史值")
                    .isNull();
            assertThat(result.getData().getUpdatedAt()).isNotNull();
            verify(service).clearApiKey("openai", null, "127.0.0.1");
        }

        @Test
        @DisplayName("service 抛 BusinessException(vendor 不存在)→ 透传")
        void service_throws_propagates() {
            when(service.clearApiKey("ghost", null, "127.0.0.1"))
                    .thenThrow(new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                            "vendor=ghost 在 ai_vendor_config 不存在"));

            assertThatThrownBy(() -> controller.clearApiKey("ghost", request))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }
    }

    // ─────────────────── 既有 update 端点验证未被破坏 ───────────────────

    @Nested
    @DisplayName("Phase 2 既有 update 端点未被破坏(回归)")
    class RegressionOnExistingUpdate {

        @Test
        @DisplayName("PUT /{vendor} 仍然工作 — 不影响既有 VendorConfigDto 流程")
        void existing_update_still_works() {
            AiVendorConfig saved = newCfg(1L, "openai", "https://new", true);
            when(service.update(eq("openai"), any())).thenReturn(saved);

            com.nexusforge.ai.controller.dto.VendorConfigDto dto =
                    new com.nexusforge.ai.controller.dto.VendorConfigDto();
            dto.setBaseUrl("https://new");

            Result<com.nexusforge.ai.controller.vo.VendorConfigVo> result = controller.update("openai", dto);

            assertThat(result).isNotNull();
            assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(result.getData().getBaseUrl()).isEqualTo("https://new");
            verify(service).update("openai", dto);
        }
    }

    // ─────────────────── helpers ───────────────────

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
