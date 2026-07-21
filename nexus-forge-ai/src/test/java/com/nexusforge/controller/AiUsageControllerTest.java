package com.nexusforge.controller;

import com.nexusforge.controller.vo.UsageSummaryVo;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.service.UsageAggregateRow;
import com.nexusforge.service.UsageByModelRow;
import com.nexusforge.service.UsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AiUsageController")
class AiUsageControllerTest {

    private static final Long USER_ID = 42L;
    private static final UserPrincipal PRINCIPAL = new UserPrincipal(USER_ID, "alice");

    UsageService usageService;
    AiUsageController controller;

    @BeforeEach
    void setUp() {
        usageService = mock(UsageService.class);
        controller = new AiUsageController(usageService);
    }

    // ──────────────────────────────────────────────
    // GET /api/ai/usage
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("myUsage")
    class MyUsage {

        @Test
        @DisplayName("from/to 均为 null → 传 null 给 service(由 service 填默认 24h)")
        void null_from_to_passes_nulls() {
            UsageSummaryVo vo = emptyVo();
            when(usageService.getSummary(eq(USER_ID), any(), any())).thenReturn(vo);

            var result = controller.myUsage(PRINCIPAL, null, null);

            assertThat(result.getData()).isSameAs(vo);
            verify(usageService).getSummary(eq(USER_ID), eq(null), eq(null));
        }

        @Test
        @DisplayName("指定 from/to → 透传给 service")
        void custom_from_to() {
            OffsetDateTime from = OffsetDateTime.now().minusDays(7);
            OffsetDateTime to = OffsetDateTime.now();
            UsageSummaryVo vo = emptyVo();
            when(usageService.getSummary(USER_ID, from, to)).thenReturn(vo);

            var result = controller.myUsage(PRINCIPAL, from, to);

            assertThat(result.getData()).isSameAs(vo);
            verify(usageService).getSummary(USER_ID, from, to);
        }
    }

    // ──────────────────────────────────────────────
    // GET /api/ai/usage/conversation/{id}
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("conversationUsage")
    class ConversationUsage {

        @Test
        @DisplayName("返回会话累计用量")
        void returns_conversation_usage() {
            UsageAggregateRow row = new UsageAggregateRow(100, 200, 300, 5);
            when(usageService.getConversationUsage(99L)).thenReturn(row);

            var result = controller.conversationUsage(PRINCIPAL, 99L);

            assertThat(result.getData()).isEqualTo(row);
        }
    }

    // ──────────────────────────────────────────────
    // helper
    // ──────────────────────────────────────────────

    private static UsageSummaryVo emptyVo() {
        UsageSummaryVo vo = new UsageSummaryVo();
        vo.setPromptTokens(0);
        vo.setCompletionTokens(0);
        vo.setTotalTokens(0);
        vo.setRequestCount(0);
        vo.setByModel(List.of());
        return vo;
    }
}
