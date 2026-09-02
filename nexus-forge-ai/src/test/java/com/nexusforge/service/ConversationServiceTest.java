package com.nexusforge.service;

import com.nexusforge.ai.service.PreferenceResolver;
import com.nexusforge.client.LlmClient;
import com.nexusforge.client.UsageRecorder;
import com.nexusforge.config.AiProperties;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConversationService 单元测试(Phase 6 重写)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>create:标题默认值、显式标题、字段透传</li>
 *   <li>sendMessage:正常路径持久化 user+assistant、Usage 提取、上下文构建</li>
 *   <li>sendMessage:Usage 提取不到(只有请求计数)、失败路径</li>
 *   <li>权限校验:跨用户访问抛 FORBIDDEN</li>
 *   <li>rename / pin / delete:权限校验与状态变更</li>
 *   <li>listConversations:getConversation:消息列表 + 用量映射</li>
 * </ul>
 *
 * <p>spring-ai-full-migration Phase 6 重写:用 Spring AI 的
 * {@link Prompt} / {@link ChatResponse} / {@link AssistantMessage} / {@link Usage}
 * 替代原 com.nexusforge.ai.* 旧 DTO;MessageVo 不再有 toolCalls 字段,
 * 旧 toolCalls JSON 持久化测试用例删(service 已不再处理 toolCalls)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationServiceTest {

    @Mock AiConversationRepository conversationRepo;
    @Mock AiMessageRepository messageRepo;
    @Mock AiMessageUsageRepository usageRepo;
    @Mock LlmClient llmClient;
    @Mock ContextWindowBuilder contextBuilder;
    @Mock UsageRecorder usageRecorder;
    @Mock QuotaService quotaService;
    @Mock PreferenceResolver preferenceResolver;
    @Mock EntityManager entityManager;
    @Mock AiProperties props;

    @InjectMocks ConversationService service;

    @BeforeEach
    void stubPrefResolver() {
        // @PersistenceContext 字段 Mockito 不会自动注入,手动反射注入
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        // 默认所有测试都按"系统 Key 模式"走——vendor 用模型名解析出来的 vendor,Key 用 yaml。
        // Phase 3 — PreferenceResolver 改成 3 参 (userId, model, proxyId);本测试都走 proxyId=null
        // 走旧 path,1 参和 3 参都得 mock(2 参 overload 内部也调 3 参)
        lenient().when(preferenceResolver.resolve(any(), any(), any())).thenAnswer(inv -> {
            String modelField = inv.getArgument(1);
            String vendor = "openai";
            String modelName = "gpt-4o-mini";
            if (modelField != null && !modelField.isBlank() && modelField.contains(":")) {
                String[] parts = modelField.split(":", 2);
                vendor = parts[0];
                modelName = parts[1];
            }
            return new PreferenceResolver.Resolved(
                    vendor, modelName,
                    /* apiKey */ null, /* fingerprint */ null, /* baseUrl */ null,
                    PreferenceResolver.KeySource.SYSTEM);
        });
        // 旧 2 参签名也 mock 一下(虽然实际 ConversationService 走 3 参,但保险)
        lenient().when(preferenceResolver.resolve(any(), any())).thenAnswer(inv -> {
            String modelField = inv.getArgument(1);
            String vendor = "openai";
            String modelName = "gpt-4o-mini";
            if (modelField != null && !modelField.isBlank() && modelField.contains(":")) {
                String[] parts = modelField.split(":", 2);
                vendor = parts[0];
                modelName = parts[1];
            }
            return new PreferenceResolver.Resolved(
                    vendor, modelName,
                    /* apiKey */ null, /* fingerprint */ null, /* baseUrl */ null,
                    PreferenceResolver.KeySource.SYSTEM);
        });
    }

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

        AiMessage userSaved = newMessage(10L, 0, "USER", "你好");
        AiMessage aiSaved = newMessage(11L, 1, "ASSISTANT", "你好!有什么可以帮你的?");
        when(messageRepo.save(any(AiMessage.class)))
                .thenReturn(userSaved)
                .thenReturn(aiSaved);

        when(contextBuilder.build(any(), eq("openai:gpt-4o-mini")))
                .thenReturn(List.<Message>of(new UserMessage("你好")));

        ChatResponse llmResp = chatResponse("你好!有什么可以帮你的?", "gpt-4o-mini", 10, 20, 30);
        when(llmClient.call(any(Prompt.class), eq("openai"), eq("gpt-4o-mini"))).thenReturn(llmResp);

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
    @DisplayName("sendMessage: 无 usage metadata → 只调 recordRequest,token counter 不报")
    void sendMessage_without_usage_only_records_request() {
        AiConversation conv = baseConv();

        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.of(conv));
        when(messageRepo.findMaxSeq(1L)).thenReturn(-1);
        when(messageRepo.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of());
        when(messageRepo.save(any(AiMessage.class))).thenAnswer(inv -> {
            AiMessage m = inv.getArgument(0);
            if (m.getId() == null) m.setId(10L);
            return m;
        });

        when(contextBuilder.build(any(), any())).thenReturn(List.of());
        when(llmClient.call(any(Prompt.class), any(String.class), any(String.class)))
                .thenReturn(chatResponseNoUsage("ok", "gpt-4o-mini"));

        SendMessageDto dto = new SendMessageDto();
        dto.setContent("hi");

        service.sendMessage(100L, 1L, dto);

        verify(usageRepo, never()).save(any(AiMessageUsage.class));
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
        when(llmClient.call(any(Prompt.class), any(String.class), any(String.class)))
                .thenReturn(chatResponse("ok", "gpt-4o-mini", 1, 1, 2));
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
        when(llmClient.call(any(Prompt.class), eq("openai"), eq("gpt-4o")))
                .thenReturn(chatResponse("hi", "gpt-4o", 1, 1, 2));
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

        AiMessage lastMsg = newMessage(99L, 4, "ASSISTANT", "最后一句话");
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

        AiMessage userMsg = newMessage(10L, 0, "USER", "hi");
        AiMessage aiMsg = newMessage(11L, 1, "ASSISTANT", "hello back");
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
    @DisplayName("deleteConversation: 跨用户访问抛 FORBIDDEN,无 repo.delete 调用")
    void delete_throws_when_not_owned() {
        when(conversationRepo.findByIdAndUserId(1L, 100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteConversation(100L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.FORBIDDEN.getCode());

        verify(conversationRepo, never()).deleteById(anyLong());
    }

    // ──────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────

    private AiConversation baseConv() {
        AiConversation conv = new AiConversation();
        conv.setId(1L);
        conv.setUserId(100L);
        conv.setTitle("新对话");
        conv.setModel("openai:gpt-4o-mini");
        conv.setPinned(false);
        return conv;
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

    /**
     * 构造 Spring AI {@link ChatResponse}:有 usage metadata 的标准响应。
     * Spring AI 2.0 的 Usage 字段是 Integer,不是 Long。
     */
    private static ChatResponse chatResponse(String content, String model, int prompt, int completion, int total) {
        AssistantMessage msg = new AssistantMessage(content);
        Usage usage = new Usage() {
            @Override public Integer getPromptTokens() { return prompt; }
            @Override public Integer getCompletionTokens() { return completion; }
            @Override public Integer getTotalTokens() { return total; }
            @Override public Object getNativeUsage() { return null; }
        };
        org.springframework.ai.chat.metadata.ChatResponseMetadata md =
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .model(model)
                        .usage(usage)
                        .build();
        return new ChatResponse(List.of(new Generation(msg)), md);
    }

    /** 构造无 usage metadata 的 ChatResponse(老 vendor 不报 token 的场景)。 */
    private static ChatResponse chatResponseNoUsage(String content, String model) {
        AssistantMessage msg = new AssistantMessage(content);
        return new ChatResponse(List.of(new Generation(msg)));
    }
}
