package com.nexusforge.service;

import com.nexusforge.ai.service.PreferenceResolver;
import com.nexusforge.base.PageResult;
import com.nexusforge.client.LlmClient;
import com.nexusforge.client.UsageRecorder;
import com.nexusforge.controller.dto.CreateConversationDto;
import com.nexusforge.controller.dto.SendMessageDto;
import com.nexusforge.controller.dto.UpdateTitleDto;
import com.nexusforge.controller.vo.ConversationDetailVo;
import com.nexusforge.controller.vo.ConversationVo;
import com.nexusforge.controller.vo.MessageVo;
import com.nexusforge.controller.vo.UsageVo;
import com.nexusforge.entity.AiConversation;
import com.nexusforge.entity.AiMessage;
import com.nexusforge.entity.AiMessageUsage;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.repository.AiConversationRepository;
import com.nexusforge.repository.AiMessageRepository;
import com.nexusforge.repository.AiMessageUsageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * spring-ai-full-migration Phase 2b — 业务侧主流程。
 *
 * <p>sendMessage 改为 Spring AI 类型:
 * <ul>
 *   <li>DB 的 {@link AiMessage} 仍是 source of truth(暂不重写列结构)</li>
 *   <li>送进 LLM 的消息列表由 {@link ContextWindowBuilder} 产出 Spring AI {@link Message}</li>
 *   <li>LlmClient 调 {@code call(Prompt, vendor, model)} 拿 Spring AI {@link ChatResponse}</li>
 *   <li>助手回复从 Spring AI {@link AssistantMessage} 转换回 {@link AiMessage} 持久化</li>
 *   <li>tool loop 暂未实现 — Phase 3 改用 {@code ToolCallingManager} + {@code ChatClient} 链</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final AiConversationRepository conversationRepo;
    private final AiMessageRepository messageRepo;
    private final AiMessageUsageRepository usageRepo;
    private final LlmClient llmClient;
    private final ContextWindowBuilder contextBuilder;
    private final UsageRecorder usageRecorder;
    private final QuotaService quotaService;
    private final PreferenceResolver preferenceResolver;

    @PersistenceContext
    private EntityManager entityManager;

    // ──────────────────────────────────────────────
    // 创建会话
    // ──────────────────────────────────────────────

    @Transactional
    public ConversationVo create(Long userId, CreateConversationDto dto) {
        AiConversation conv = new AiConversation();
        conv.setUserId(userId);
        conv.setTitle(dto.getTitle() != null ? dto.getTitle() : "新对话");
        conv.setModel(dto.getModel());
        conv.setPinned(false);
        conversationRepo.save(conv);

        log.info("[AI] 创建会话: userId={}, convId={}, model={}", userId, conv.getId(), conv.getModel());
        return toConversationVo(conv, 0L, null);
    }

    // ──────────────────────────────────────────────
    // 发送消息(Phase 2b:无 tool loop)
    // ──────────────────────────────────────────────

    @Transactional
    public MessageVo sendMessage(Long userId, Long conversationId, SendMessageDto dto) {
        // 1. 校验对话归属
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));

        // 如果前端传了 model,覆盖对话模型
        if (dto.getModel() != null && !dto.getModel().isBlank()) {
            conv.setModel(dto.getModel());
        }

        // 2. 解析偏好 — Phase 3:第三参 proxyId 优先级最高(覆盖对话默认 model)
        PreferenceResolver.Resolved pref = preferenceResolver.resolve(userId, conv.getModel(), dto.getProxyId());

        // 3. 配额校验(私 Key 模式直接放行)
        quotaService.check(userId, estimateTokens(dto.getContent()), pref.source());

        // 4. 持久化用户消息
        int nextSeq = messageRepo.findMaxSeq(conversationId) + 1;
        AiMessage userMsg = new AiMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        userMsg.setContent(dto.getContent());
        userMsg.setSeq(nextSeq);
        messageRepo.save(userMsg);

        // 5. 构建上下文窗口(Spring AI Message 列表)
        List<AiMessage> history = messageRepo.findByConversationIdOrderBySeqAsc(conversationId);
        List<Message> contextMessages = contextBuilder.build(history, conv.getModel());
        Prompt prompt = new Prompt(contextMessages);

        // 6. 调用 LLM(按 key source 走不同路径)
        ChatResponse response;
        try {
            if (pref.source() == PreferenceResolver.KeySource.USER_PRIVATE_KEY) {
                ChatModel privateModel = preferenceResolver.resolveChatModel(pref);
                // Phase 3:用实际 vendor 校验 + model 写进 prompt options
                Prompt callPrompt = LlmClient.withModelInOptions(prompt, pref.vendor(), pref.model());
                response = llmClient.call(callPrompt, privateModel, pref.vendor());
            } else {
                response = llmClient.call(prompt, pref.vendor(), pref.model());
            }
        } catch (Exception e) {
            log.error("[AI] LLM 调用失败: convId={}, model={}, mode={}", conversationId, conv.getModel(), pref.source(), e);
            // 失败路径也计数
            usageRecorder.recordRequest(conv.getModel(), pref.source());
            throw e;
        }

        // 7. 从 Spring AI ChatResponse 提取助手回复
        AssistantMessage assistantMsg = extractAssistantMessage(response);
        String content = assistantMsg != null && assistantMsg.getText() != null ? assistantMsg.getText() : "";

        // 8. 持久化 AI 回复
        AiMessage aiMsg = new AiMessage();
        aiMsg.setConversationId(conversationId);
        aiMsg.setRole("ASSISTANT");
        aiMsg.setContent(content);
        aiMsg.setSeq(nextSeq + 1);
        messageRepo.save(aiMsg);

        // 9. 持久化 token 用量(Spring AI Usage)
        Usage usage = extractUsage(response);
        if (usage != null) {
            AiMessageUsage usageEntity = new AiMessageUsage();
            usageEntity.setMessageId(aiMsg.getId());
            usageEntity.setPromptTokens(usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : 0);
            usageEntity.setCompletionTokens(usage.getCompletionTokens() != null ? usage.getCompletionTokens().intValue() : 0);
            usageEntity.setTotalTokens(usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : 0);
            usageEntity.setModel(response.getMetadata() != null ? response.getMetadata().getModel() : conv.getModel());
            usageRepo.save(usageEntity);
            usageRecorder.recordMetrics(usage, conv.getModel(), pref.source());
        } else {
            usageRecorder.recordRequest(conv.getModel(), pref.source());
        }

        // 10. 自动更新标题
        if ("新对话".equals(conv.getTitle())) {
            String autoTitle = dto.getContent().length() <= 30
                    ? dto.getContent()
                    : dto.getContent().substring(0, 30) + "...";
            conv.setTitle(autoTitle);
        }

        log.info("[AI] 消息已处理: convId={}, seq={}, contentLen={}",
                conversationId, aiMsg.getSeq(), content.length());

        return toMessageVo(aiMsg, usage);
    }

    /**
     * 从 Spring AI ChatResponse 提取第一个 Generation 的 AssistantMessage。
     */
    private static AssistantMessage extractAssistantMessage(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        return response.getResults().get(0).getOutput();
    }

    private static Usage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return null;
        return response.getMetadata().getUsage();
    }

    // ──────────────────────────────────────────────
    // 查询
    // ──────────────────────────────────────────────

    public List<ConversationVo> listConversations(Long userId) {
        List<AiConversation> convs = conversationRepo.findByUserIdOrderByPinnedDescUpdatedAtDesc(userId);
        return convs.stream().map(this::toVoWithPreview).toList();
    }

    public PageResult<ConversationVo> listConversationsPaged(Long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = (size <= 0 || size > 200) ? 20 : size;

        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<AiConversation> convPage = conversationRepo
                .findByUserIdOrderByPinnedDescUpdatedAtDesc(userId, pageable);
        Page<ConversationVo> voPage = convPage.map(this::toVoWithPreview);
        return PageResult.of(voPage);
    }

    private ConversationVo toVoWithPreview(AiConversation c) {
        Long msgCount = messageRepo.countByConversationId(c.getId());
        List<AiMessage> lastMsgs = messageRepo.findLastNMessages(c.getId(), 1);
        String lastMsg = lastMsgs.isEmpty() ? null : lastMsgs.get(0).getContent();
        if (lastMsg != null && lastMsg.length() > 100) {
            lastMsg = lastMsg.substring(0, 100) + "...";
        }
        return toConversationVo(c, msgCount, lastMsg);
    }

    public ConversationDetailVo getConversation(Long userId, Long conversationId) {
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));

        List<AiMessage> messages = messageRepo.findByConversationIdOrderBySeqAsc(conversationId);
        List<Long> messageIds = messages.stream().map(AiMessage::getId).toList();
        List<MessageVo> messageVos = messages.stream().map(m -> {
            AiMessageUsage usage = usageRepo.findById(m.getId()).orElse(null);
            return toMessageVo(m, usage);
        }).toList();

        ConversationDetailVo vo = new ConversationDetailVo();
        vo.setId(conv.getId());
        vo.setTitle(conv.getTitle());
        vo.setModel(conv.getModel());
        vo.setPinned(conv.getPinned());
        vo.setMessages(messageVos);
        vo.setCreatedAt(conv.getCreatedAt());
        vo.setUpdatedAt(conv.getUpdatedAt());
        return vo;
    }

    // ──────────────────────────────────────────────
    // 修改
    // ──────────────────────────────────────────────

    @Transactional
    public ConversationVo renameConversation(Long userId, Long conversationId, UpdateTitleDto dto) {
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));
        conv.setTitle(dto.getTitle());
        Long msgCount = messageRepo.countByConversationId(conversationId);
        return toConversationVo(conv, msgCount, null);
    }

    @Transactional
    public ConversationVo pinConversation(Long userId, Long conversationId, boolean pinned) {
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));
        conv.setPinned(pinned);
        Long msgCount = messageRepo.countByConversationId(conversationId);
        return toConversationVo(conv, msgCount, null);
    }

    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));
        conversationRepo.delete(conv);
        log.info("[AI] 软删会话: userId={}, convId={}", userId, conversationId);
    }

    @Transactional
    public void restoreConversation(Long userId, Long conversationId) {
        int restored = entityManager.createNativeQuery(
                "UPDATE ai_conversations SET deleted_at = NULL, updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = :id AND user_id = :userId AND deleted_at IS NOT NULL")
                .setParameter("id", conversationId)
                .setParameter("userId", userId)
                .executeUpdate();
        if (restored == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "对话不存在、未软删或无权访问");
        }
        log.info("[AI] 恢复会话: userId={}, convId={}", userId, conversationId);
    }

    // ──────────────────────────────────────────────
    // VO 转换
    // ──────────────────────────────────────────────

    private ConversationVo toConversationVo(AiConversation conv, Long messageCount, String lastMessage) {
        ConversationVo vo = new ConversationVo();
        vo.setId(conv.getId());
        vo.setTitle(conv.getTitle());
        vo.setModel(conv.getModel());
        vo.setPinned(conv.getPinned());
        vo.setMessageCount(messageCount);
        vo.setLastMessage(lastMessage);
        vo.setCreatedAt(conv.getCreatedAt());
        vo.setUpdatedAt(conv.getUpdatedAt());
        return vo;
    }

    /**
     * Phase 2b 简化版:tool_calls 字段暂不返回(MessageVo 字段保留但始终 null)。
     * Phase 3 重做 tool loop 时再恢复。
     */
    private MessageVo toMessageVo(AiMessage msg, Usage usage) {
        MessageVo vo = new MessageVo();
        vo.setId(msg.getId());
        vo.setRole(msg.getRole());
        vo.setContent(msg.getContent());
        vo.setSeq(msg.getSeq());
        vo.setCreatedAt(msg.getCreatedAt());
        if (usage != null) {
            UsageVo uv = new UsageVo();
            uv.setPromptTokens(usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : 0);
            uv.setCompletionTokens(usage.getCompletionTokens() != null ? usage.getCompletionTokens().intValue() : 0);
            uv.setTotalTokens(usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : 0);
            vo.setUsage(uv);
        }
        return vo;
    }

    /** 兼容旧调用(从 DB 加载 usage 实体的路径) */
    private MessageVo toMessageVo(AiMessage msg, AiMessageUsage usage) {
        MessageVo vo = new MessageVo();
        vo.setId(msg.getId());
        vo.setRole(msg.getRole());
        vo.setContent(msg.getContent());
        vo.setSeq(msg.getSeq());
        vo.setCreatedAt(msg.getCreatedAt());
        if (usage != null) {
            UsageVo uv = new UsageVo();
            uv.setPromptTokens(usage.getPromptTokens());
            uv.setCompletionTokens(usage.getCompletionTokens());
            uv.setTotalTokens(usage.getTotalTokens());
            vo.setUsage(uv);
        }
        return vo;
    }

    /**
     * P5 Step 8:粗估输入 token 数,用于配额预检。
     */
    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 16;
        return text.length() / 2 + 16;
    }
}
