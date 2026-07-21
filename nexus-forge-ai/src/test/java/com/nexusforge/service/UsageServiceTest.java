package com.nexusforge.service;

import com.nexusforge.controller.vo.UsageSummaryVo;
import com.nexusforge.repository.AiMessageUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * UsageService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UsageService")
class UsageServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long CONV_ID = 42L;

    @Mock AiMessageUsageRepository usageRepo;
    @InjectMocks UsageService usageService;

    @Test
    @DisplayName("getSummary: 空窗口 → 全 0 + 空 byModel")
    void getSummary_empty_window() {
        when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                .thenReturn(UsageAggregateRow.empty());
        when(usageRepo.sumByUserModelWindow(eq(USER_ID), any(), any()))
                .thenReturn(List.of());

        UsageSummaryVo vo = usageService.getSummary(USER_ID);

        assertThat(vo.getPromptTokens()).isZero();
        assertThat(vo.getCompletionTokens()).isZero();
        assertThat(vo.getTotalTokens()).isZero();
        assertThat(vo.getRequestCount()).isZero();
        assertThat(vo.getByModel()).isEmpty();
    }

    @Test
    @DisplayName("getSummary: 有数据 → 正确映射 + byModel 按 totalTokens 降序")
    void getSummary_with_data() {
        when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                .thenReturn(new UsageAggregateRow(5000, 3000, 8000, 20));
        when(usageRepo.sumByUserModelWindow(eq(USER_ID), any(), any()))
                .thenReturn(List.of(
                        new UsageByModelRow("gpt-4o", 3000, 2000, 5000, 10),
                        new UsageByModelRow("gpt-4o-mini", 2000, 1000, 3000, 10)
                ));

        UsageSummaryVo vo = usageService.getSummary(USER_ID);

        assertThat(vo.getPromptTokens()).isEqualTo(5000);
        assertThat(vo.getCompletionTokens()).isEqualTo(3000);
        assertThat(vo.getTotalTokens()).isEqualTo(8000);
        assertThat(vo.getRequestCount()).isEqualTo(20);
        assertThat(vo.getByModel()).hasSize(2);
        assertThat(vo.getByModel().get(0).getModel()).isEqualTo("gpt-4o");
        assertThat(vo.getByModel().get(0).getTotalTokens()).isEqualTo(5000);
        assertThat(vo.getByModel().get(1).getModel()).isEqualTo("gpt-4o-mini");
        assertThat(vo.getByModel().get(1).getRequestCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("getConversationUsage: 有数据 → 委托到 usageRepo")
    void getConversationUsage_delegates() {
        UsageAggregateRow expected = new UsageAggregateRow(100, 50, 150, 3);
        when(usageRepo.sumByConversation(CONV_ID)).thenReturn(expected);

        UsageAggregateRow result = usageService.getConversationUsage(CONV_ID);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("getConversationUsage: 空会话 → 返回 empty()")
    void getConversationUsage_empty() {
        when(usageRepo.sumByConversation(CONV_ID)).thenReturn(UsageAggregateRow.empty());

        UsageAggregateRow result = usageService.getConversationUsage(CONV_ID);

        assertThat(result.promptTokens()).isZero();
        assertThat(result.requestCount()).isZero();
    }
}
