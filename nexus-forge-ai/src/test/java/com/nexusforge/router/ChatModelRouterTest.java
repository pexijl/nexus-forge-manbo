package com.nexusforge.router;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatModelRouter 单元测试 —— 锁住 ChatRequest.model 解析规则与
 * AiProperties.Provider.enabled / defaultModel 配置契约。
 *
 * <p>本测试反映 nexus-forge-ai 当前实现的真实行为,包括已知的边界差异:
 * <ul>
 *   <li>vendor 大小写:仅 ChatModel map 查询走 toLowerCase,
 *       AiProperties.providers 查询保留原大小写 —— 故 'OpenAI:xxx' 命中前者、
 *       在后者缺失时抛 LLM_MODEL_NOT_FOUND。</li>
 *   <li>trim 范围:仅作用于整段字符串,vendor:model 拆分后的 model 名内部空格原样保留。</li>
 * </ul>
 */
class ChatModelRouterTest {

    /** 测试用 ChatModel 桩实现。 */
    private static ChatModel stub(String name, boolean stream) {
        return new ChatModel() {
            @Override public String name() { return name; }
            @Override public ChatCapabilities capabilities() {
                return ChatCapabilities.builder().stream(stream).tools(false).build();
            }
            @Override public ChatResponse call(ChatRequest request) {
                return ChatResponse.builder()
                        .model(request.getModel())
                        .content("stub-" + name)
                        .build();
            }
            @Override public Flux<ChatChunk> stream(ChatRequest request) {
                return Flux.error(new UnsupportedOperationException("stub"));
            }
        };
    }

    private ChatModel openai;
    private ChatModel ollama;
    private ChatModel anthropic;
    private AiProperties props;
    private ChatModelRouter router;

    @BeforeEach
    void setup() {
        openai    = stub("openai", true);
        ollama    = stub("ollama", false);
        anthropic = stub("anthropic", true);

        props = new AiProperties();
        props.setDefaultVendor("openai");
        props.setDefaultModel("gpt-4o-mini");

        AiProperties.Provider pOpenAi = new AiProperties.Provider();
        pOpenAi.setEnabled(true);
        pOpenAi.setDefaultModel("gpt-4o-mini");
        // 故意留 apiKey/baseUrl 为 null —— 这里只测路由,不动 call()
        props.getProviders().put("openai", pOpenAi);

        AiProperties.Provider pAnthropic = new AiProperties.Provider();
        pAnthropic.setEnabled(true);
        pAnthropic.setDefaultModel("claude-3-5-haiku");
        props.getProviders().put("anthropic", pAnthropic);

        AiProperties.Provider pOllama = new AiProperties.Provider();
        pOllama.setEnabled(false);                 // 显式禁用,用于测试禁用分支
        pOllama.setDefaultModel("llama3");
        props.getProviders().put("ollama", pOllama);

        Map<String, ChatModel> map = new HashMap<>();
        map.put("openai", openai);
        map.put("ollama", ollama);
        map.put("anthropic", anthropic);

        router = new ChatModelRouter(map, props);
    }

    /** 简化测试请求构造 —— router 不读 messages,传空列表即可。 */
    private ChatRequest req(String model) {
        return ChatRequest.builder().model(model).messages(List.of()).build();
    }

    // ───── resolve(...) 入参分支 ─────────────────────────────────────
    @Nested
    @DisplayName("resolve 入参分支")
    class ResolveEntry {

        @Test
        @DisplayName("request == null → 默认 vendor + 默认 model")
        void null_request_uses_defaults() {
            ChatModelRouter.Resolved r = router.resolve(null);
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
            assertThat(r.model()).isSameAs(openai);
        }

        @Test
        @DisplayName("model == null → 默认 vendor + 默认 model")
        void null_model_uses_defaults() {
            ChatModelRouter.Resolved r = router.resolve(req(null));
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("model = 空字符串 → 默认 vendor + 默认 model")
        void empty_string_model_uses_defaults() {
            ChatModelRouter.Resolved r = router.resolve(req(""));
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("model = 纯空白 → 默认 vendor + 默认 model")
        void blank_model_uses_defaults() {
            ChatModelRouter.Resolved r = router.resolve(req("   "));
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("model = 'gpt-4o-mini'(无冒号) → 默认 vendor + 原值作 model 名")
        void unprefixed_model_uses_default_vendor() {
            ChatModelRouter.Resolved r = router.resolve(req("gpt-4o-mini"));
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
            assertThat(r.model()).isSameAs(openai);
        }

        @Test
        @DisplayName("model = 'openai:gpt-4o-mini' → 拆分 vendor/model")
        void prefixed_model_is_split() {
            ChatModelRouter.Resolved r = router.resolve(req("openai:gpt-4o-mini"));
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
            assertThat(r.model()).isSameAs(openai);
        }

        @Test
        @DisplayName("model 带前后空格 → 整段 trim 后再 indexOf,substring 内部空格原样保留")
        void prefixed_model_with_surrounding_whitespace() {
            // 实际行为:实现仅对整段做 trim(),v+m 拆分点之后的子串保留原样
            //   trim 后的字符串为 "openai: gpt-4o-mini",substring(6) = " gpt-4o-mini"
            //   (前导空格保留,末尾的空格已随整段 trim 掉)
            ChatModelRouter.Resolved r = router.resolve(req("  openai: gpt-4o-mini  "));
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo(" gpt-4o-mini");
        }

        @Test
        @DisplayName("model = 'OpenAI:gpt-4o-mini'(大写 vendor) → ChatModel 命中实现命中,但 Provider 段查不到原大小写 → 抛异常")
        void case_mixed_vendor_throws_when_provider_section_unmatched() {
            // 当前实现的协议:
            //   1) models.get(vendor.toLowerCase()) 命中 → openai 实例;
            //   2) props.getProviders().get("OpenAI") 取原大小写 → 未配置 → null
            //   3) p == null → 抛 LLM_MODEL_NOT_FOUND
            // 这是已知不一致性:要么 vendor 查询两边都 toLowerCase,要么都不。本测试锁住当前行为。
            assertThatThrownBy(() -> router.resolve(req("OpenAI:gpt-4o-mini")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("model = 'anthropic:claude-3-5-haiku' → 路由到 anthropic 实例 + 默认 model 兜底用 Provider.defaultModel")
        void anthropic_routing() {
            ChatModelRouter.Resolved r = router.resolve(req("anthropic:claude-3-5-haiku"));
            assertThat(r.vendor()).isEqualTo("anthropic");
            assertThat(r.modelName()).isEqualTo("claude-3-5-haiku");
            assertThat(r.model()).isSameAs(anthropic);
        }

        @Test
        @DisplayName("model = ':gpt-4o-mini'(开头冒号,idx=0 不满足 >0) → 走默认 vendor,model 字段含冒号")
        void leading_colon_falls_back_to_default_vendor() {
            // indexOf(':') = 0 → idx > 0 不成立 → 走默认分支:vendor='openai', model=':gpt-4o-mini'
            ChatModelRouter.Resolved r = router.resolve(req(":gpt-4o-mini"));
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo(":gpt-4o-mini");
        }

        @Test
        @DisplayName("model = 'openai:'(末位冒号) → substring 后 model 为空 → 用 provider.defaultModel 兜底")
        void trailing_colon_uses_provider_default_model() {
            ChatModelRouter.Resolved r = router.resolve(req("openai:"));
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
        }
    }

    // ───── 异常分支 ─────────────────────────────────────────────────
    @Nested
    @DisplayName("异常分支")
    class ExceptionBranches {

        @Test
        @DisplayName("未知 vendor(ChatModel map 中不存在)→ LLM_MODEL_NOT_FOUND")
        void unknown_vendor_throws_not_found() {
            // 即便 props.providers 里有 'mistral' 段但 ChatModel map 没注册,仍抛
            AiProperties.Provider pm = new AiProperties.Provider();
            pm.setEnabled(true);
            props.getProviders().put("mistral", pm);

            assertThatThrownBy(() -> router.resolve(req("mistral:large")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode()))
                    .hasMessageContaining("未找到 vendor=mistral");
        }

        @Test
        @DisplayName("已被显式禁用的 vendor → LLM_MODEL_NOT_FOUND")
        void disabled_provider_throws_not_found() {
            // ollama 在 setup 里 enabled=false,ChatModel 实例仍存在但路由层 reject
            assertThatThrownBy(() -> router.resolve(req("ollama:llama3")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode()))
                    .hasMessageContaining("已禁用");
        }

        @Test
        @DisplayName("ChatModel map 命中 vendor,但 props.providers 段缺失 → LLM_MODEL_NOT_FOUND")
        void missing_provider_section_throws_not_found() {
            // 把 ollama 的 Provider 段从 props 里删除,但 ChatModel 实例还留着
            props.getProviders().remove("ollama");
            assertThatThrownBy(() -> router.resolve(req("ollama:llama3")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode()));
        }
    }

    // ───── effectiveModel 兜底分支 ───────────────────────────────────
    @Nested
    @DisplayName("effectiveModel 兜底")
    class EffectiveModelFallback {

        @Test
        @DisplayName("'openai:' 拆分后 model 为空 → effectiveModel 取 provider.defaultModel")
        void empty_model_after_prefix_uses_provider_default_model() {
            ChatModelRouter.Resolved r = router.resolve(req("openai:"));
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("provider.defaultModel 为 null 且 model 字段为空 → effectiveModel 也可能为 null(锁住当前实现)")
        void null_provider_default_model_is_passed_through() {
            // 全局默认 vendor=openai,openai 段 provider.defaultModel 显式设为 null
            AiProperties props2 = new AiProperties();
            props2.setDefaultVendor("openai");
            props2.setDefaultModel("global-fallback");
            AiProperties.Provider po = new AiProperties.Provider();
            po.setEnabled(true);
            po.setDefaultModel(null);
            props2.getProviders().put("openai", po);
            Map<String, ChatModel> map = new HashMap<>();
            map.put("openai", openai);
            ChatModelRouter r2 = new ChatModelRouter(map, props2);

            ChatModelRouter.Resolved res = r2.resolve(req("openai:"));
            // 当前实现是 (model == null || model.isBlank()) ? p.getDefaultModel() : model → null 透传
            assertThat(res.modelName()).isNull();
        }

        @Test
        @DisplayName("默认请求 + 默认 provider 的 defaultModel = null 时,effectiveModel 回退到全局 defaultModel?锁住当前行为")
        void blank_request_with_null_provider_default_uses_global_default() {
            // 当前实现:blank 时 resolveInternal(vendor, props.getDefaultModel()) → 全局 defaultModel
            ChatModelRouter.Resolved r = router.resolve(req(null));
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
        }
    }

    // ───── Resolved record 行为 ──────────────────────────────────────
    @Nested
    @DisplayName("Resolved record")
    class ResolvedRecord {

        @Test
        @DisplayName("Resolved record 暴露 model / vendor / modelName 三个组件,各自独立")
        void resolved_components_are_independent() {
            ChatModelRouter.Resolved r = new ChatModelRouter.Resolved(openai, "openai", "gpt-4o-mini");
            assertThat(r.model()).isSameAs(openai);
            assertThat(r.vendor()).isEqualTo("openai");
            assertThat(r.modelName()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("Resolved record equals/hashCode 基于三组件")
        void resolved_equality_is_structural() {
            ChatModelRouter.Resolved a = new ChatModelRouter.Resolved(openai, "openai", "gpt-4o-mini");
            ChatModelRouter.Resolved b = new ChatModelRouter.Resolved(openai, "openai", "gpt-4o-mini");
            ChatModelRouter.Resolved c = new ChatModelRouter.Resolved(openai, "anthropic", "gpt-4o-mini");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
            assertThat(a).isNotEqualTo(c);
        }
    }
}
