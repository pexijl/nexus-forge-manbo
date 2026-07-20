package com.nexusforge.service;

import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.ChatUsage;
import com.nexusforge.ai.Role;
import com.nexusforge.client.LlmClient;
import com.nexusforge.controller.dto.CreateConversationDto;
import com.nexusforge.controller.dto.SendMessageDto;
import com.nexusforge.controller.dto.UpdateTitleDto;
import com.nexusforge.controller.vo.ConversationDetailVo;
import com.nexusforge.controller.vo.ConversationVo;
import com.nexusforge.controller.vo.MessageVo;
import com.nexusforge.entity.AiConversation;
import com.nexusforge.entity.AiMessage;
import com.nexusforge.entity.AiMessageUsage;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.repository.AiConversationRepository;
import com.nexusforge.repository.AiMessageRepository;
import com.nexusforge.repository.AiMessageUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationService 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>create:标题默认值、显式标题、字段透传</li>
 *   <li>sendMessage:正常路径持久化 user+assistant 两端、自动标题、用量、上下文构建</li>
 *   <li>权限校验:跨用户访问抛 FORBIDDEN</li>
 *   <li>rename / pin / delete:权限校验与状态变更</li>
 *   <li>getConversation:包含消息列表与用量</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationServiceTest {

    @Mock AiConversationRepository conversationRepo;
    @Mock AiMessageRepository messageRepo;
    @Mock AiMessageUsageRepository usageRepo;
    @Mock LlmClient llmClient;
    @Mock ContextWindowBuilder contextBuilder;

    @InjectMocks ConversationService service;

    // ──────────────────────────────────────────
    // create
    // ──────────────────────────────────────────

    @Test
    @DisplayName("create: 显式标题和模型正确透传")
    void create_sets_title_and_model() {
        CreateConversationDto dto = new CreateConversationDto();
        dto.setModel("openai:gpt-4o-mini");
        dto.setTitle("测试对话");

        when(conversationRepo.save(any(AiConversation.class))).thenAnswer(inv -> {
            AiConversation c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        ConversationVo vo = service.create(100L, dto);

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getTitle()).isEqualTo("测试对话");
        assertThat(vo.getModel()).isEqualTo("openai:gpt-4o-mini");
        assertThat(vo.getPinned()).isFalse();
        assertThat(vo.getMessageCount()).isZero();

        ArgumentCaptor<AiConversation> captor = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationRepo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(100L);
        assertThat(captor.getValue().getPinned()).isFalse();
    }

    @Test
    @DisplayName("create: 标题为 null 时使用默认标题 '新对话'")
    void create_uses_default_title_when_null() {
        CreateConversationDto dto = new CreateConversationDto();
        dto.setModel("openai:gpt-4o-mini");

        when(conversationRepo.save(any(AiConversation.class))).thenAnswer(inv -> {
            AiConversation c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        ConversationVo vo = service.create(100L, dto);

        assertThat(vo.getTitle()).isEqualTo("新对话");
    }

    // ──────────────────────────────────────────
    // sendMessage
    // ──────────────────────────────────────────

    @Test
    @DisplayName("sendMessage: 持久化 user+assistant 两端消息,返回 ASSISTANT VO")
    void sendMessage_persists_user_and_ai_messages() {
        AiConversation conv = baseConv();

        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(conv));
        when(messageRepo.findMaxSeq(1L)).thenReturn(-1);
        when(messageRepo.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of());

        AiMessage userSaved = newMessage(10L, 0, Role.USER.name(), "你好");
        AiMessage aiSaved = newMessage(11L, 1, Role.ASSISTANT.name(), "你好!有什么可以帮你的?");
        when(messageRepo.save(any(AiMessage.class)))
                .thenReturn(userSaved)
                .thenReturn(aiSaved);

        when(contextBuilder.build(any(), eq("openai:gpt-4o-mini")))
                .thenReturn(List.of(ChatMessage.builder().role(Role.USER).content("你好").build()));

        when(llmClient.call(any())).thenReturn(ChatResponse.builder()
                .id("resp-1")
                .model("gpt-4o-mini")
                .content("你好!有什么可以帮你的?")
                .usage(ChatUsage.builder().promptTokens(10).completionTokens(20).totalTokens(30).build())
                .finishReason("stop")
                .build());

        when(usageRepo.save(any(AiMessageUsage.class))).thenReturn(null);

        SendMessageDto dto = new SendMessageDto();
        dto.setContent("你好");

        MessageVo vo = service.sendMessage(100L, 1L, dto);

        assertThat(vo.getContent()).isEqualTo("你好!有什么可以帮你的?");
        assertThat(vo.getRole()).isEqualTo("ASSISTANT");
        assertThat(vo.getUsage()).isNotNull();
        assertThat(vo.getUsage().getTotalTokens()).isEqualTo(30);
        assertThat(vo.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(vo.getUsage().getCompletionTokens()).isEqualTo(20);

        // 验证:save 调用 2 次(user + assistant),usage save 调用 1 次
        verify(messageRepo, times(2)).save(any(AiMessage.class));
        verify(usageRepo).save(any(AiMessageUsage.class));
    }

    @Test
    @DisplayName("sendMessage: 首条 USER 消息自动更新默认标题")
    void sendMessage_auto_updates_title() {
        AiConversation conv = baseConv();
        conv.setTitle("新对话");

        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(conv));
        when(messageRepo.findMaxSeq(1L)).thenReturn(-1);
        when(messageRepo.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of());
        when(messageRepo.save(any(AiMessage.class))).thenAnswer(inv -> {
            AiMessage m = inv.getArgument(0);
            m.setId(10L);
            return m;
        });
        when(contextBuilder.build(any(), any())).thenReturn(List.of());
        when(llmClient.call(any())).thenReturn(ChatResponse.builder()
                .content("ok")
                .model("gpt-4o-mini")
                .usage(ChatUsage.builder().promptTokens(1).completionTokens(1).totalTokens(2).build())
                .build());
        when(usageRepo.save(any())).thenReturn(null);

        SendMessageDto dto = new SendMessageDto();
        dto.setContent("这是一个超过 30 个字符用于测试自动标题截断功能的长消息串,请确保超过 30 阈值");

        service.sendMessage(100L, 1L, dto);

        // 30 字符截断,加 "..."
        assertThat(conv.getTitle()).hasSizeLessThanOrEqualTo(33);
        assertThat(conv.getTitle()).endsWith("...");
    }

    @Test
    @DisplayName("sendMessage: dto.model 非空时覆盖对话模型")
    void sendMessage_overrides_model_when_dto_specifies() {
        AiConversation conv = baseConv();
        conv.setModel("openai:gpt-4o-mini");

        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(conv));
        when(messageRepo.findMaxSeq(1L)).thenReturn(-1);
        when(messageRepo.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of());
        when(messageRepo.save(any(AiMessage.class))).thenAnswer(inv -> {
            AiMessage m = inv.getArgument(0);
            m.setId(10L);
            return m;
        });
        when(contextBuilder.build(any(), eq("openai:gpt-4o"))).thenReturn(List.of());
        when(llmClient.call(any())).thenReturn(ChatResponse.builder()
                .content("hi")
                .model("gpt-4o")
                .usage(ChatUsage.builder().promptTokens(1).completionTokens(1).totalTokens(2).build())
                .build());
        when(usageRepo.save(any())).thenReturn(null);

        SendMessageDto dto = new SendMessageDto();
        dto.setContent("切换模型");
        dto.setModel("openai:gpt-4o");

        service.sendMessage(100L, 1L, dto);

        // 上下文构建使用切换后的模型
        verify(contextBuilder).build(any(), eq("openai:gpt-4o"));
    }

    @Test
    @DisplayName("sendMessage: 对话不属于当前用户时抛 FORBIDDEN")
    void sendMessage_throws_when_conversation_not_owned() {
        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.empty());

        SendMessageDto dto = new SendMessageDto();
        dto.setContent("hello");

        assertThatThrownBy(() -> service.sendMessage(100L, 1L, dto))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.FORBIDDEN.getCode());
    }

    // ──────────────────────────────────────────
    // listConversations
    // ──────────────────────────────────────────

    @Test
    @DisplayName("listConversations: 返回的 VO 含消息计数和最近消息预览")
    void list_conversations_includes_count_and_preview() {
        AiConversation c1 = baseConv();
        c1.setId(1L);
        c1.setTitle("标题 1");
        c1.setPinned(true);

        AiConversation c2 = new AiConversation();
        c2.setId(2L);
        c2.setUserId(100L);
        c2.setTitle("标题 2");
        c2.setModel("openai:gpt-4o");
        c2.setPinned(false);

        when(conversationRepo.findByUserIdOrderByPinnedDescUpdatedAtDesc(100L))
                .thenReturn(List.of(c1, c2));
        when(messageRepo.countByConversationId(1L)).thenReturn(5L);
        when(messageRepo.countByConversationId(2L)).thenReturn(0L);

        AiMessage lastMsg = newMessage(99L, 4, Role.ASSISTANT.name(), "最后一句话");
        when(messageRepo.findLastNMessages(1L, 1)).thenReturn(List.of(lastMsg));
        when(messageRepo.findLastNMessages(2L, 1)).thenReturn(List.of());

        List<ConversationVo> vos = service.listConversations(100L);

        assertThat(vos).hasSize(2);
        assertThat(vos.get(0).getId()).isEqualTo(1L);
        assertThat(vos.get(0).getMessageCount()).isEqualTo(5L);
        assertThat(vos.get(0).getLastMessage()).isEqualTo("最后一句话");
        assertThat(vos.get(1).getLastMessage()).isNull();
    }

    // ──────────────────────────────────────────
    // getConversation
    // ──────────────────────────────────────────

    @Test
    @DisplayName("getConversation: 返回的 messages 含用量映射")
    void get_conversation_includes_usage_per_message() {
        AiConversation conv = baseConv();
        conv.setId(1L);

        AiMessage userMsg = newMessage(10L, 0, Role.USER.name(), "hi");
        AiMessage aiMsg = newMessage(11L, 1, Role.ASSISTANT.name(), "hello back");
        AiMessageUsage usage = new AiMessageUsage();
        usage.setMessageId(11L);
        usage.setPromptTokens(7);
        usage.setCompletionTokens(13);
        usage.setTotalTokens(20);
        usage.setModel("gpt-4o-mini");

        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(conv));
        when(messageRepo.findByConversationIdOrderBySeqAsc(1L))
                .thenReturn(List.of(userMsg, aiMsg));
        when(usageRepo.findById(10L)).thenReturn(Optional.empty());
        when(usageRepo.findById(11L)).thenReturn(Optional.of(usage));

        ConversationDetailVo vo = service.getConversation(100L, 1L);

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getMessages()).hasSize(2);
        assertThat(vo.getMessages().get(0).getRole()).isEqualTo("USER");
        assertThat(vo.getMessages().get(0).getUsage()).isNull();
        assertThat(vo.getMessages().get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(vo.getMessages().get(1).getUsage()).isNotNull();
        assertThat(vo.getMessages().get(1).getUsage().getTotalTokens()).isEqualTo(20);
    }

    // ──────────────────────────────────────────
    // rename / pin / delete
    // ──────────────────────────────────────────

    @Test
    @DisplayName("renameConversation: 更新标题并返回新 VO")
    void rename_updates_title() {
        AiConversation conv = baseConv();
        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(conv));
        when(messageRepo.countByConversationId(1L)).thenReturn(3L);

        UpdateTitleDto dto = new UpdateTitleDto();
        dto.setTitle("新标题");

        ConversationVo vo = service.renameConversation(100L, 1L, dto);

        assertThat(conv.getTitle()).isEqualTo("新标题");
        assertThat(vo.getTitle()).isEqualTo("新标题");
        assertThat(vo.getMessageCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("pinConversation: 切换置顶状态")
    void pin_toggles_state() {
        AiConversation conv = baseConv();
        conv.setPinned(false);
        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(conv));
        when(messageRepo.countByConversationId(1L)).thenReturn(0L);

        ConversationVo vo = service.pinConversation(100L, 1L, true);

        assertThat(conv.getPinned()).isTrue();
        assertThat(vo.getPinned()).isTrue();
    }

    @Test
    @DisplayName("deleteConversation: 成功删除时静默返回")
    void delete_succeeds() {
        when(conversationRepo.deleteByIdAndUserId(1L, 100L)).thenReturn(1);

        service.deleteConversation(100L, 1L);

        verify(conversationRepo).deleteByIdAndUserId(1L, 100L);
    }

    @Test
    @DisplayName("deleteConversation: 删除 0 行时抛 FORBIDDEN")
    void delete_throws_when_no_match() {
        when(conversationRepo.deleteByIdAndUserId(1L, 100L)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteConversation(100L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    // ──────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────

    private AiConversation baseConv() {
        AiConversation c = new AiConversation();
        c.setId(1L);
        c.setUserId(100L);
        c.setTitle("测试对话");
        c.setModel("openai:gpt-4o-mini");
        c.setPinned(false);
        return c;
    }

    private AiMessage newMessage(long id, int seq, String role, String content) {
        AiMessage m = new AiMessage();
        m.setId(id);
        m.setConversationId(1L);
        m.setRole(role);
        m.setContent(content);
        m.setSeq(seq);
        return m;
    }
}