package com.nexusforge.client;

import tools.jackson.databind.JsonNode;

/**
 * 工具执行 SPI。由业务侧(例如未来的 {@code nexus-forge-visual} 图表查询、本仓库
 * 的内置 {@code EchoTool})实现,通过 {@link ToolRegistry} 注册,
 * 由 {@link LlmClient#callWithToolLoop} 在 LLM 返回 {@code finishReason="tool_calls"}
 * 时调用。
 *
 * <p>设计要点:
 * <ul>
 *   <li>{@link #name()} 必须全局唯一,重复名会导致 {@link ToolRegistry} 启动期
 *       fail-fast(抛 {@link IllegalStateException})。</li>
 *   <li>{@link #execute(JsonNode)} 不应抛异常 —— 失败请返回
 *       {@link ToolResult#error(String)};循环层会兜底 catch,但正确做法是工具
 *       自行捕获并以 error 结果返回,这样 LLM 能"看到"失败并决定是否降级。</li>
 *   <li>工具实现应该是无状态的(singleton bean);如需状态(连接池等)请注入到
 *       工具类自己的字段里,不要用 ctor 参数。</li>
 * </ul>
 *
 * <p>P4 Step 12 落地。本 PR 仅同步路径生效;流式路径(Step 12+)另起。
 */
public interface ToolExecutor {

    /**
     * 工具注册名。OpenAI 协议下与上游 {@code tools[].function.name} 一致;
     * Anthropic 协议下与上游 {@code tools[].name} 一致。
     * 大小写敏感,推荐小写 + 下划线。
     */
    String name();

    /**
     * 执行工具。{@code arguments} 由上游 mapper 解析为 {@link JsonNode},
     * 通常对应 OpenAI 的 {@code function.arguments} 字符串(已 parse)或
     * Anthropic 的 {@code tool_use.input} 对象。
     *
     * @return 工具结果(内容 + 是否错误标记)。返回的字符串会作为
     *         {@code role=TOOL, name=<tool_call_id>, content=<result>} 消息
     *         回灌给 LLM,触发下一轮推理。
     */
    ToolResult execute(JsonNode arguments);
}
