package com.nexusforge.service;
import com.nexusforge.ai.*;
import com.nexusforge.client.LlmClient;
import com.nexusforge.client.UsageRecorder;
import com.nexusforge.controller.dto.CreateConversationDto;
import com.nexusforge.controller.dto.SendMessageDto;
import com.nexusforge.controller.dto.UpdateTitleDto;
import com.nexusforge.controller.vo.*;
import com.nexusforge.entity.AiConversation;
import com.nexusforge.entity.AiMessage;
import com.nexusforge.entity.AiMessageUsage;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.repository.AiConversationRepository;
import com.nexusforge.repository.AiMessageRepository;
import com.nexusforge.repository.AiMessageUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

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
    /**
     * P4 Step 11:把 {@link ChatResponse#getToolCalls()} 序列化为 JSON 写入
     * {@link AiMessage#getToolCalls()} 列。这里用 {@code tools.jackson} 包路径
     * 与 Spring 7 Boot 默认 {@code tools.jackson.databind.ObjectMapper} bean 一致。
     */
    private final ObjectMapper objectMapper;

    // ──────────────────────────────────────────────
    // 创建会话

    /**
     * 创建新对话。可选传入 system prompt 作为首条消息。
     */
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
    // 发送消息(核心:上下文构建 + LLM 调用 + 持久化)
    // ──────────────────────────────────────────────

    /**
     * 在对话中发送用户消息并获取 AI 回复。
     * <ol>
     *   <li>校验对话归属</li>
     *   <li>持久化用户消息</li>
     *   <li>从 DB 加载历史消息,构建上下文窗口</li>
     *   <li>调用 LlmClient.call()</li>
     *   <li>持久化 AI 回复 + token 用量</li>
     * </ol>
     */
    @Transactional
    public MessageVo sendMessage(Long userId, Long conversationId, SendMessageDto dto) {
        // 1. 校验对话归属
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));

        // P5 Step 5:配额校验(24h 滑窗 token / 请求次数)。超出抛 LLM_QUOTA_EXCEEDED → HTTP 429
        quotaService.check(userId);

        // 如果前端传了 model,覆盖对话模型(切换模型)
        if (dto.getModel() != null && !dto.getModel().isBlank()) {
            conv.setModel(dto.getModel());
        }

        // 2. 持久化用户消息
        int nextSeq = messageRepo.findMaxSeq(conversationId) + 1;
        AiMessage userMsg = new AiMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole(Role.USER.name());
        userMsg.setContent(dto.getContent());
        userMsg.setSeq(nextSeq);
        messageRepo.save(userMsg);

        // 3. 构建上下文窗口
        List<AiMessage> history = messageRepo.findByConversationIdOrderBySeqAsc(conversationId);
        List<ChatMessage> context = contextBuilder.build(history, conv.getModel());

        // 4. 调用 LLM
        ChatRequest request = ChatRequest.builder()
                .model(conv.getModel())
                .messages(context)
                .build();

        ChatResponse response;
        try {
            response = llmClient.call(request);
        } catch (Exception e) {
            log.error("[AI] LLM 调用失败: convId={}, model={}", conversationId, conv.getModel(), e);
            // P5 Step 4: 失败路径也计数,告警面板能统计失败率(否则失败请求从 metrics 中消失)
            usageRecorder.recordRequest(conv.getModel());
            throw e;
        }

        // 5. 持久化 AI 回复
        AiMessage aiMsg = new AiMessage();
        aiMsg.setConversationId(conversationId);
        aiMsg.setRole(Role.ASSISTANT.name());
        aiMsg.setContent(response.getContent());
        aiMsg.setSeq(nextSeq + 1);
        // P4 Step 11:assistant 触发工具调用时,把 tool_calls 序列化为 JSON 写入专用列。
        // 当前不执行工具、不重入 LLM —— 完整 function-calling 循环是后续 Step 12+。
        // 此处仅做"曝光":对话详情接口能读到 assistant 选择调用了哪些工具、入参是什么。
        String toolCallsJson = null;
        if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
            try {
                toolCallsJson = objectMapper.writeValueAsString(response.getToolCalls());
                aiMsg.setToolCalls(toolCallsJson);
            } catch (Exception e) {
                // JSON 序列化失败不应阻塞消息持久化,只记日志
                log.warn("[AI] tool_calls JSON 序列化失败: convId={}, seq={}, err={}",
                        conversationId, aiMsg.getSeq(), e.toString());
            }
        }
        messageRepo.save(aiMsg);

        // 6. 持久化 token 用量
        if (response.getUsage() != null) {
            AiMessageUsage usage = new AiMessageUsage();
            usage.setMessageId(aiMsg.getId());
            usage.setPromptTokens(response.getUsage().getPromptTokens());
            usage.setCompletionTokens(response.getUsage().getCompletionTokens());
            usage.setTotalTokens(response.getUsage().getTotalTokens());
            usage.setModel(response.getModel());
            usageRepo.save(usage);

            // P5 Step 4: 把本次 LLM 调用的 token 消耗上报为 Micrometer counter
            // (ai.chat.requests / tokens.{prompt,completion,total})。埋点失败
            // 不影响主链路,UsageRecorder 内部吞异常。
            usageRecorder.recordMetrics(response.getUsage(), response.getModel());
        }

        // 7. 自动更新标题(取第一条 USER 消息前 30 字)
        if ("新对话".equals(conv.getTitle())) {
            String autoTitle = dto.getContent().length() <= 30
                    ? dto.getContent()
                    : dto.getContent().substring(0, 30) + "...";
            conv.setTitle(autoTitle);
        }

        log.info("[AI] 消息已处理: convId={}, seq={}, tokens={}, toolCalls={}",
                conversationId, aiMsg.getSeq(),
                response.getUsage() != null ? response.getUsage().getTotalTokens() : "N/A",
                toolCallsJson != null ? "yes" : "no");

        return toMessageVo(aiMsg, response.getUsage(), response.getToolCalls());
    }

    // ──────────────────────────────────────────────
    // 查询
    // ──────────────────────────────────────────────

    /**
     * 列出用户的全部对话(置顶优先,更新时间倒序)
     */
    public List<ConversationVo> listConversations(Long userId) {
        List<AiConversation> convs = conversationRepo.findByUserIdOrderByPinnedDescUpdatedAtDesc(userId);
        return convs.stream().map(c -> {
            Long msgCount = messageRepo.countByConversationId(c.getId());
            // 获取最后一条消息预览
            List<AiMessage> lastMsgs = messageRepo.findLastNMessages(c.getId(), 1);
            String lastMsg = lastMsgs.isEmpty() ? null : lastMsgs.get(0).getContent();
            if (lastMsg != null && lastMsg.length() > 100) {
                lastMsg = lastMsg.substring(0, 100) + "...";
            }
            return toConversationVo(c, msgCount, lastMsg);
        }).toList();
    }

    /**
     * 获取对话详情(含全部消息)
     */
    public ConversationDetailVo getConversation(Long userId, Long conversationId) {
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));

        List<AiMessage> messages = messageRepo.findByConversationIdOrderBySeqAsc(conversationId);
        // 批量加载用量(避免 N+1)
        List<Long> messageIds = messages.stream().map(AiMessage::getId).toList();
        // 简单实现:P3 先逐条查,P5 可优化为 IN 查询
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

    /**
     * 重命名对话标题
     */
    @Transactional
    public ConversationVo renameConversation(Long userId, Long conversationId, UpdateTitleDto dto) {
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));
        conv.setTitle(dto.getTitle());
        Long msgCount = messageRepo.countByConversationId(conversationId);
        return toConversationVo(conv, msgCount, null);
    }

    /**
     * 置顶/取消置顶
     */
    @Transactional
    public ConversationVo pinConversation(Long userId, Long conversationId, boolean pinned) {
        AiConversation conv = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问"));
        conv.setPinned(pinned);
        Long msgCount = messageRepo.countByConversationId(conversationId);
        return toConversationVo(conv, msgCount, null);
    }

    /**
     * 删除对话(级联删除消息和用量)
     */
    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        int deleted = conversationRepo.deleteByIdAndUserId(conversationId, userId);
        if (deleted == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "对话不存在或无权访问");
        }
        log.info("[AI] 删除会话: userId={}, convId={}", userId, conversationId);
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

    private MessageVo toMessageVo(AiMessage msg, ChatUsage usage) {
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
     * P4 Step 11:同步路径下,assistant 回复触发了 tool_calls 时,把列表一并带到 VO;
     * 流式路径(暂无 sendMessage 调用方)暂未触发此路径。
     */
    private MessageVo toMessageVo(AiMessage msg, ChatUsage usage, java.util.List<ToolCall> toolCalls) {
        MessageVo vo = toMessageVo(msg, usage);
        vo.setToolCalls(toolCalls);
        return vo;
    }

    // 重载:接受 AiMessageUsage 实体
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
}