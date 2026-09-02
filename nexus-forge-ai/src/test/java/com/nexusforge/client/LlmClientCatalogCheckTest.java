package com.nexusforge.client;

import com.nexusforge.ai.entity.AiModelCatalog;
import com.nexusforge.ai.service.FallbackChainService;
import com.nexusforge.ai.service.ModelCatalogService;
import com.nexusforge.config.AiProperties;
import com.nexusforge.config.AiProperties.Provider;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.router.ChatModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 1 — LlmClient catalog 校验测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>call(prompt, vendor, model):catalog 不存在 → LLM_MODEL_NOT_FOUND(不进 ChatModel)</li>
 *   <li>call(prompt, vendor, model):catalog 存在但 enabled=false → LLM_MODEL_DISABLED(不进 ChatModel)</li>
 *   <li>call(prompt, vendor, model):catalog 存在且 enabled=true → 正常调 ChatModel</li>
 *   <li>call(prompt):无显式 vendor/model,查解析后的 primary catalog → 同样三态</li>
 *   <li>call(prompt, ChatModel):私 Key 路径也走 catalog 校验(硬门禁,不让私 Key 绕过 admin disable)</li>
 * </ul>
 *
 * <p>不覆盖:fallback chain / tool loop / 流式超时(已有别的测试覆盖);
 * 这里只验证"catalog gate 在 ChatModel 之前生效"的不变量。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LlmClient — Phase 1 catalog 校验")
class LlmClientCatalogCheckTest {

    @Mock ModelCatalogService catalogService;
    @Mock com.nexusforge.ai.provider.SystemKeyChatModelFactory systemKeyFactory;
    @Mock FallbackChainService fallbackChainService;

    private LlmClient client;
    private ChatModel stubOpenAi;
    private AiProperties props;

    @BeforeEach
    void setUp() {
        stubOpenAi = new StubChatModel("stub-openai");

        // 配 openai vendor enabled,default-model gpt-4o-mini
        props = new AiProperties();
        props.setEnabled(true);
        props.setDefaultVendor("openai");
        props.setDefaultModel("gpt-4o-mini");
        Provider openaiProvider = new Provider();
        openaiProvider.setEnabled(true);
        openaiProvider.setDefaultModel("gpt-4o-mini");
        props.setProviders(Map.of("openai", openaiProvider));

        ChatModelRouter router = new ChatModelRouter(Map.of("openAiChatModel", stubOpenAi), props, fallbackChainService);
        // Phase 7 — 本测试不关心降级链,给个空 chain stub(router 走 service 不再走 props)
        lenient().when(fallbackChainService.findEffective()).thenReturn(
                new com.nexusforge.ai.service.FallbackChainService.FallbackChainView(
                        List.of(), com.nexusforge.ai.service.FallbackChainService.FallbackChainSource.EMPTY, null));
        // Phase 5 — systemKeyFactory.resolveOrCreate 替 LlmClient 拿 ChatModel。
        // 现有 catalog check 测试不关心 factory 内部,把它 stub 成直接返回 stubOpenAi。
        when(systemKeyFactory.resolveOrCreate(any())).thenReturn(stubOpenAi);
        client = new LlmClient(router, props, List.of(), catalogService, systemKeyFactory);
    }

    // ─────────────── call(prompt, vendor, model) — 显式 ───────────────

    @Test
    @DisplayName("显式 (vendor, model) 不在 catalog → LLM_MODEL_NOT_FOUND,不进 ChatModel")
    void explicit_unknown_model_throws_not_found() {
        when(catalogService.findByVendorModel("openai", "unknown-model")).thenReturn(null);

        Prompt p = new Prompt(List.of(new UserMessage("hi")));
        assertThatThrownBy(() -> client.call(p, "openai", "unknown-model"))
                .isInstanceOf(LlmException.class)
                .matches(e -> {
                    LlmException le = (LlmException) e;
                    return le.getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode()
                            && le.getMessage().contains("openai")
                            && le.getMessage().contains("unknown-model");
                });
    }

    @Test
    @DisplayName("显式 (vendor, model) 在 catalog 但 enabled=false → LLM_MODEL_DISABLED,不进 ChatModel")
    void explicit_disabled_model_throws_disabled() {
        AiModelCatalog disabled = catalog(1L, "openai", "gpt-4o-mini", false);
        when(catalogService.findByVendorModel("openai", "gpt-4o-mini")).thenReturn(disabled);

        Prompt p = new Prompt(List.of(new UserMessage("hi")));
        assertThatThrownBy(() -> client.call(p, "openai", "gpt-4o-mini"))
                .isInstanceOf(LlmException.class)
                .matches(e -> {
                    LlmException le = (LlmException) e;
                    return le.getCode() == ResultCode.LLM_MODEL_DISABLED.getCode()
                            && le.getMessage().contains("禁用");
                });
    }

    @Test
    @DisplayName("显式 (vendor, model) 在 catalog 且 enabled=true → 正常调 ChatModel")
    void explicit_enabled_model_proceeds() {
        AiModelCatalog enabled = catalog(1L, "openai", "gpt-4o-mini", true);
        when(catalogService.findByVendorModel("openai", "gpt-4o-mini")).thenReturn(enabled);

        Prompt p = new Prompt(List.of(new UserMessage("hi")));
        ChatResponse resp = client.call(p, "openai", "gpt-4o-mini");
        // 关键断言:catalog check 放行后真的调到了 ChatModel — 不抛异常即代表 catalog gate OK
        assertThat(resp).isNotNull();
        assertThat(resp.getResult()).isNotNull();
    }

    // ─────────────── call(prompt) — 隐式(走 primary 解析) ───────────────

    @Test
    @DisplayName("无显式 (vendor, model):解析后 primary 不在 catalog → LLM_MODEL_NOT_FOUND")
    void implicit_unknown_primary_throws() {
        // router.resolveWithFallback(null, null) → 用 props.defaultVendor("openai") + defaultModel("gpt-4o-mini")
        // catalog 查 (openai, gpt-4o-mini) 返回 null
        when(catalogService.findByVendorModel(eq("openai"), eq("gpt-4o-mini"))).thenReturn(null);

        Prompt p = new Prompt(List.of(new UserMessage("hi")));
        assertThatThrownBy(() -> client.call(p))
                .isInstanceOf(LlmException.class)
                .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("无显式 (vendor, model):解析后 primary 被禁用 → LLM_MODEL_DISABLED")
    void implicit_disabled_primary_throws() {
        when(catalogService.findByVendorModel(eq("openai"), eq("gpt-4o-mini")))
                .thenReturn(catalog(1L, "openai", "gpt-4o-mini", false));

        Prompt p = new Prompt(List.of(new UserMessage("hi")));
        assertThatThrownBy(() -> client.call(p))
                .isInstanceOf(LlmException.class)
                .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_MODEL_DISABLED.getCode());
    }

    // ─────────────── call(prompt, ChatModel) — 私 Key ───────────────

    @Test
    @DisplayName("私 Key 路径:catalog enabled → 正常调(用户 key 调模型)")
    void private_key_path_enabled_proceeds() {
        when(catalogService.findByVendorModel(eq("openai"), eq("gpt-4o-mini")))
                .thenReturn(catalog(1L, "openai", "gpt-4o-mini", true));

        // 私 Key 路径要求 prompt.getOptions().getModel() 有值
        Prompt p = new Prompt(List.of(new UserMessage("hi")),
                OpenAiChatOptions.builder().model("gpt-4o-mini").build());
        ChatResponse resp = client.call(p, stubOpenAi);
        // 关键断言:catalog check 放行后真的调到了 ChatModel
        assertThat(resp).isNotNull();
        assertThat(resp.getResult()).isNotNull();
    }

    @Test
    @DisplayName("私 Key 路径:catalog disabled → LLM_MODEL_DISABLED(admin 硬门禁,私 Key 也不能绕过)")
    void private_key_path_disabled_throws() {
        when(catalogService.findByVendorModel(eq("openai"), eq("gpt-4o-mini")))
                .thenReturn(catalog(1L, "openai", "gpt-4o-mini", false));

        Prompt p = new Prompt(List.of(new UserMessage("hi")),
                OpenAiChatOptions.builder().model("gpt-4o-mini").build());
        assertThatThrownBy(() -> client.call(p, stubOpenAi))
                .isInstanceOf(LlmException.class)
                .matches(e -> ((LlmException) e).getCode() == ResultCode.LLM_MODEL_DISABLED.getCode());
    }

    // ───────────────── helper ─────────────────

    private static AiModelCatalog catalog(Long id, String vendor, String model, boolean enabled) {
        AiModelCatalog m = new AiModelCatalog();
        try {
            var f = AiModelCatalog.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (Exception ignored) { /* test reflection */ }
        m.setVendor(vendor);
        m.setModelName(model);
        m.setEnabled(enabled);
        return m;
    }

    /** 极简 ChatModel 测试桩。 */
    private static class StubChatModel implements ChatModel {
        private final String name;
        StubChatModel(String name) { this.name = name; }
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(name))));
        }
        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }
}
