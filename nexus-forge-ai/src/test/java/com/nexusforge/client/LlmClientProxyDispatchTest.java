package com.nexusforge.client;

import com.nexusforge.ai.entity.AiModelCatalog;
import com.nexusforge.ai.service.ModelCatalogService;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.router.ChatModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 3 — {@link LlmClient#call(Prompt, ChatModel, String)} 新 3 参重载测试。
 *
 * <p>背景:旧 {@code call(Prompt, ChatModel)} 把 catalog 校验的 vendor 硬写为
 * {@code "openai"}。Phase 3 用户走 {@code user_ai_proxy} 时 vendor 是 deepseek /
 * dashscope / glm 等任意 OpenAI 兼容 vendor,需要新重载传实际 vendor,否则
 * admin 禁用 deepseek 模型时私 Key 用户仍能调通,绕过合规门禁。
 *
 * <p>覆盖:
 * <ul>
 *   <li>正常路径:实际 vendor "deepseek" + model "deepseek-v3" → catalog 查 (deepseek, deepseek-v3) 通过 → 调 ChatModel.call</li>
 *   <li>catalog 拒绝(实际 vendor 不在 enabled)→ 抛 LLM_MODEL_DISABLED</li>
 *   <li>catalog 拒绝(实际 vendor 不存在)→ 抛 LLM_MODEL_NOT_FOUND</li>
 *   <li>old 2 参 call 仍工作(向后兼容,Phase 1-2 调用方不改)— 行为不变</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LlmClient — Phase 3 call(Prompt, ChatModel, vendor) 私 Key 实际 vendor 校验")
class LlmClientProxyDispatchTest {

    @Mock ModelCatalogService catalogService;
    @Mock ChatModelRouter router;
    @Mock ChatModel privateChatModel;
    @Mock com.nexusforge.ai.provider.SystemKeyChatModelFactory systemKeyFactory;

    private LlmClient client;
    private AiProperties props;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        client = new LlmClient(router, props, List.of(), catalogService, systemKeyFactory);
    }

    // ─────────────────── 3 参 call 实际 vendor 校验 ───────────────────

    @Nested
    @DisplayName("call(Prompt, ChatModel, actualVendor)")
    class CallWithActualVendor {

        @Test
        @DisplayName("正常路径:catalog 校验通过 (deepseek, deepseek-v3),调 ChatModel.call")
        void passes_catalog_and_calls_chatmodel() {
            // catalog 允许 deepseek/deepseek-v3
            AiModelCatalog allowed = newModel("deepseek", "deepseek-v3", true);
            when(catalogService.findByVendorModel(eq("deepseek"), eq("deepseek-v3"))).thenReturn(allowed);

            // ChatModel 返 ChatResponse
            ChatResponse resp = chatResp("deepseek-v3", "你好", 5, 7);
            when(privateChatModel.call(any(Prompt.class))).thenReturn(resp);

            Prompt prompt = promptWithModel("deepseek-v3");
            ChatResponse result = client.call(prompt, privateChatModel, "deepseek");

            assertThat(result).isSameAs(resp);
            verify(catalogService).findByVendorModel("deepseek", "deepseek-v3");
            verify(privateChatModel).call(any(Prompt.class));
        }

        @Test
        @DisplayName("catalog 拒绝:enabled=false → LLM_MODEL_DISABLED(用实际 vendor,不是 'openai')")
        void catalog_disabled_throws() {
            AiModelCatalog disabled = newModel("deepseek", "deepseek-v3", false);
            when(catalogService.findByVendorModel("deepseek", "deepseek-v3")).thenReturn(disabled);

            Prompt prompt = promptWithModel("deepseek-v3");

            assertThatThrownBy(() -> client.call(prompt, privateChatModel, "deepseek"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("deepseek")
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_MODEL_DISABLED.getCode());

            // 关键:catalog 是按实际 vendor 查的,不是按 "openai"
            verify(catalogService).findByVendorModel("deepseek", "deepseek-v3");
            verify(catalogService, never()).findByVendorModel(eq("openai"), any());
        }

        @Test
        @DisplayName("catalog 不存在 → LLM_MODEL_NOT_FOUND")
        void catalog_not_found_throws() {
            when(catalogService.findByVendorModel("anthropic", "claude-3")).thenReturn(null);

            Prompt prompt = promptWithModel("claude-3");

            assertThatThrownBy(() -> client.call(prompt, privateChatModel, "anthropic"))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("model 在 prompt options 缺失时 → 跳过 catalog 校验(防御性)")
        void null_model_in_prompt_skips_catalog() {
            // prompt 没设 model → extractModelName 返回 null → assertModelAllowed 跳过
            Prompt promptNoModel = new Prompt(List.of(new UserMessage("hi")));   // 无 options
            when(privateChatModel.call(any(Prompt.class))).thenReturn(chatResp("?", "ok", 1, 1));

            // 不抛错,正常调 ChatModel
            ChatResponse result = client.call(promptNoModel, privateChatModel, "deepseek");
            assertThat(result).isNotNull();
            verify(catalogService, never()).findByVendorModel(any(), any());
        }

        @Test
        @DisplayName("ChatModel 抛异常 → 包成 LLM_PROVIDER_ERROR")
        void chatmodel_exception_wrapped() {
            AiModelCatalog allowed = newModel("deepseek", "deepseek-v3", true);
            when(catalogService.findByVendorModel("deepseek", "deepseek-v3")).thenReturn(allowed);
            when(privateChatModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("upstream 502"));

            Prompt prompt = promptWithModel("deepseek-v3");

            assertThatThrownBy(() -> client.call(prompt, privateChatModel, "deepseek"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("私 Key 调用失败")
                    .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_PROVIDER_ERROR.getCode());
        }
    }

    // ─────────────────── 旧 2 参 call 仍工作(向后兼容) ───────────────────

    @Nested
    @DisplayName("旧 call(Prompt, ChatModel) 向后兼容")
    class LegacyCallCompat {

        @Test
        @DisplayName("旧 2 参 call:vendor 仍写 'openai',catalog 查 openai(行为不变)")
        void legacy_call_uses_openai_vendor() {
            // catalog 允许 openai/gpt-4o-mini
            AiModelCatalog allowed = newModel("openai", "gpt-4o-mini", true);
            when(catalogService.findByVendorModel("openai", "gpt-4o-mini")).thenReturn(allowed);
            when(privateChatModel.call(any(Prompt.class)))
                    .thenReturn(chatResp("gpt-4o-mini", "ok", 1, 1));

            Prompt prompt = promptWithModel("gpt-4o-mini");
            ChatResponse result = client.call(prompt, privateChatModel);

            assertThat(result).isNotNull();
            // 关键:旧 2 参路径仍用 vendor="openai"
            verify(catalogService).findByVendorModel("openai", "gpt-4o-mini");
        }
    }

    // ─────────────────── helpers ───────────────────

    private static AiModelCatalog newModel(String vendor, String modelName, boolean enabled) {
        AiModelCatalog m = new AiModelCatalog();
        m.setVendor(vendor);
        m.setModelName(modelName);
        m.setEnabled(enabled);
        return m;
    }

    private static ChatResponse chatResp(String model, String content, int prompt, int completion) {
        AssistantMessage msg = new AssistantMessage(content);
        Generation g = new Generation(msg);
        org.springframework.ai.chat.metadata.ChatResponseMetadata meta =
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .model(model)
                        .build();
        return new ChatResponse(List.of(g), meta);
    }

    private static Prompt promptWithModel(String model) {
        // 显式构造 OpenAiChatOptions 带 model,模拟 LlmClient.withModelInOptions 的输出
        ChatOptions opts = OpenAiChatOptions.builder().model(model).build();
        return new Prompt(List.of(new UserMessage("hi")), opts);
    }
}
