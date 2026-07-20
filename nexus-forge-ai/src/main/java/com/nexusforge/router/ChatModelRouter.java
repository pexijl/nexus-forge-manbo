package com.nexusforge.router;

import com.nexusforge.ai.ChatRequest;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatModel;
import com.nexusforge.provider.support.ChatModelHttpSupport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 大模型路由处理器
 * 根据 ChatRequest 中传入的 model 字段匹配、选择对应的 ChatModel 实现类
 * 模型名称解析命名约定：
 * <pre>
 *   "openai:gpt-4o-mini"        -> openai 服务商，模型 gpt-4o-mini
 *   "gpt-4o-mini"               -> 使用全局默认服务商(默认 openai)，模型 gpt-4o-mini
 *   "anthropic:claude-3-5-haiku" -> anthropic 服务商，模型 claude-3-5-haiku
 * </pre>
 *
 * <p>P4 Step 8 在原有 {@link #resolve(ChatRequest)} 之上新增 {@link #resolveWithFallback(ChatRequest)}:
 * 解析首选 vendor 后,按 {@code spring.ai.fallback-chain} 配置的有序候选 vendor 列表展开成
 * {@link FallbackChain},调用方(通常是 {@code LlmClient})按顺序尝试,遇到
 * {@link #isFallbackTriggering(LlmException, String) 触发降级的错误}或
 * 首选 vendor 当前处于熔断态(由 {@link ChatModelHttpSupport#isVendorOpen(String)} 查询),
 * 自动跳到下一跳。
 */
public class ChatModelRouter {

    /**
     * 所有已加载的大模型SPI实现映射
     * key：服务商名称（ChatModel#name() 返回值），value：对应服务商ChatModel实现实例
     */
    private final Map<String, ChatModel> models;

    /**
     * AI全局配置属性，读取默认厂商、默认模型、各服务商配置开关
     */
    private final AiProperties props;

    /**
     * HTTP 支持(熔断状态查询)。{@link #resolveWithFallback(ChatRequest)} 通过它判断
     * 首选 vendor 是否已经熔断。null 时降级为"不检查熔断"。
     */
    private final ChatModelHttpSupport http;

    /**
     * 兼容性 2 参构造:供现有测试 {@code new ChatModelRouter(models, props)} 使用。
     * 实际效果等价于 {@code new ChatModelRouter(models, props, null)}。
     */
    public ChatModelRouter(Map<String, ChatModel> models, AiProperties props) {
        this(models, props, null);
    }

    /**
     * P4 构造:完整参数。{@code http} 为 null 时{@link #resolveWithFallback(ChatRequest)}
     * 退化为"不检查熔断,只看异常码"。
     */
    public ChatModelRouter(Map<String, ChatModel> models,
                           AiProperties props,
                           ChatModelHttpSupport http) {
        this.models = models;
        this.props = props;
        this.http = http;
    }

    /**
     * 对外入口方法：解析请求中的模型标识，路由匹配对应服务商与有效模型名
     * @param request 对话请求对象，携带原始model标识
     * @return 路由解析结果，包含模型实现、服务商、最终生效模型名
     */
    public Resolved resolve(ChatRequest request) {
        // 请求为空 / model字段为空空白，使用全局默认厂商+全局默认模型
        if (request == null || request.getModel() == null || request.getModel().isBlank()) {
            return resolveInternal(props.getDefaultVendor(), props.getDefaultModel());
        }
        String m = request.getModel().trim();
        // 匹配 "vendor:model" 格式，拆分服务商与模型名称
        int idx = m.indexOf(':');
        if (idx > 0) {
            return resolveInternal(m.substring(0, idx), m.substring(idx + 1));
        }
        // 无冒号分隔，使用全局默认服务商，传入值作为模型名
        return resolveInternal(props.getDefaultVendor(), m);
    }

    /**
     * 解析首选 + 按 {@code spring.ai.fallback-chain} 展开降级链。
     *
     * <p>返回的 {@link FallbackChain} 是不可变的快照(构建时已固化为有序 vendor 列表),
     * 调用方按 {@link FallbackChain#iterator()} 顺序尝试:
     * <ul>
     *   <li>链上的第一项 = {@link #resolve(ChatRequest)} 的结果(用户原始请求解析出来的 vendor)</li>
     *   <li>后续项 = {@code props.fallbackChain} 中排在用户 vendor 之后、且不在首选列表里的 vendor;
     *       每项都用该 vendor 的 {@link AiProperties.Provider#getDefaultModel()} 作为模型名</li>
     *   <li>同一 vendor 不会重复出现</li>
     * </ul>
     *
     * <p>首选 vendor 解析失败({@link ResultCode#LLM_MODEL_NOT_FOUND})直接抛 ——
     * 用户显式指定了一个不存在的 vendor,降级到别家是错的;同样地,vendor 配置缺失/禁用也直接抛。
     * 降级只用来应对"运行时调用失败"而不是"配置错误"。
     *
     * <p>当 {@code props.fallbackChain} 为空时,链只有一项(= {@code resolve(req)}),行为完全等同
     * 直接调用 {@code resolve(req)},但通过 {@code FallbackChain} 暴露的好处是
     * {@code LlmClient} 用统一代码处理"无降级"和"有降级"两种情况。
     *
     * @throws LlmException {@link ResultCode#LLM_MODEL_NOT_FOUND} —— 首选 vendor 不存在/禁用/未配置
     */
    public FallbackChain resolveWithFallback(ChatRequest request) {
        Resolved primary = resolve(request);  // 解析失败立刻抛
        List<Resolved> chain = new ArrayList<>();
        chain.add(primary);

        if (props.getFallbackChain() != null) {
            for (String vendor : props.getFallbackChain()) {
                if (vendor == null || vendor.isBlank()) continue;
                String v = vendor.trim();
                // 跳过已加入链的 vendor(首选 + 前面已追加的)
                if (containsVendor(chain, v)) continue;
                // 跳过的 vendor 不存在或未启用 —— 降级时不应当把"无效配置"也串进来
                ChatModel impl = models.get(v.toLowerCase());
                if (impl == null) continue;
                AiProperties.Provider p = props.getProviders().get(v);
                if (p == null || !p.isEnabled()) continue;
                chain.add(new Resolved(impl, v, p.getDefaultModel()));
            }
        }

        return new FallbackChain(List.copyOf(chain), primary.vendor());
    }

    /**
     * 判断 {@code ex} 是否应当触发降级跳到下一 vendor。
     *
     * <p>白名单与 {@link ChatModelHttpSupport#isRetryable(LlmException)} 一致:
     * 只在 {@link ResultCode#LLM_PROVIDER_ERROR} / {@link ResultCode#LLM_UPSTREAM_TIMEOUT}
     * 时降级。其他业务错误({@code LLM_RATE_LIMITED} / {@code LLM_QUOTA_EXCEEDED} /
     * {@code LLM_INVALID_REQUEST} / {@code LLM_CIRCUIT_OPEN} / {@code LLM_CONFIG_MISSING})
     * 不降级 —— 这些错误给上游重试或改 vendor 都没有意义,直接抛给调用方。
     *
     * <p>注意这里不消费 {@code vendor} 参数,签名保留 vendor 是为以后按 vendor 定制触发矩阵
     * (例如某些上游超时算"故障",某些上游超时算"限流")预留扩展点。当前实现两者一致。
     */
    public static boolean isFallbackTriggering(LlmException ex, String vendor) {
        if (ex == null) return false;
        ResultCode code = ResultCode.fromCodeValue(ex.getCode());
        return code == ResultCode.LLM_PROVIDER_ERROR
                || code == ResultCode.LLM_UPSTREAM_TIMEOUT;
    }

    /** 首选 vendor 是否已经在熔断中(http 为 null 时一律返回 false) */
    public boolean isPrimaryVendorOpen(Resolved primary) {
        return primary != null && http != null && http.isVendorOpen(primary.vendor());
    }

    private static boolean containsVendor(List<Resolved> chain, String vendor) {
        for (Resolved r : chain) {
            if (r.vendor().equalsIgnoreCase(vendor)) return true;
        }
        return false;
    }

    /**
     * 内部路由解析逻辑：根据指定服务商、原始模型名校验可用性并生成最终路由结果
     * @param vendor 服务商标识
     * @param model 请求传入的原始模型名称
     * @return 封装后的路由解析实体
     */
    private Resolved resolveInternal(String vendor, String model) {
        // 根据服务商名称获取对应的ChatModel实现，统一转小写匹配
        ChatModel impl = models.get(vendor.toLowerCase());
        if (impl == null) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND, "未找到 vendor=" + vendor);
        }
        // 获取配置文件中该服务商的独立配置
        AiProperties.Provider p = props.getProviders().get(vendor);
        // 配置不存在 或 配置显式关闭该服务商，抛出异常禁止路由
        if (p == null || !p.isEnabled()) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND, "vendor=" + vendor + " 已禁用");
        }
        // 模型名为空时，使用该服务商自身配置的默认模型
        String effectiveModel = (model == null || model.isBlank())
                ? p.getDefaultModel()
                : model;
        return new Resolved(impl, vendor, effectiveModel);
    }

    /**
     * 路由解析结果记录类
     */
    public record Resolved(ChatModel model, String vendor, String modelName) {}

    /**
     * 降级链快照。{@link ChatModelRouter#resolveWithFallback(ChatRequest)} 时一次性构建;
     * 调用方按 {@link Iterable} 顺序依次尝试每一跳。
     *
     * <p>链至少 1 项(首选),至多 1 + {@code props.fallbackChain.size()} 项(去重 + 跳过无效 vendor)。
     */
    public record FallbackChain(List<Resolved> hops, String primaryVendor) implements Iterable<Resolved> {
        @Override
        public java.util.Iterator<Resolved> iterator() {
            return hops.iterator();
        }

        /** 链长度(含首选) */
        public int size() {
            return hops.size();
        }

        /** 是否只有首选、没有降级候选(等价于直接调用 {@code resolve(req)}) */
        public boolean isSingleHop() {
            return hops.size() == 1;
        }
    }
}