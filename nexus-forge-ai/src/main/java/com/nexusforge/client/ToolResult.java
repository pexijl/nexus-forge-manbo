package com.nexusforge.client;

/**
 * 工具执行结果载体。
 *
 * <p>字段:
 * <ul>
 *   <li>{@link #content} — 工具返回的字符串内容,会作为下一轮 LLM 的
 *       {@code role=TOOL} 消息的 content。建议:
 *       <ul>
 *         <li>成功:可序列化、可读的结构化文本(JSON / 简短描述)</li>
 *         <li>失败:人类可读的错误消息</li>
 *       </ul>
 *   </li>
 *   <li>{@link #isError} — 错误标记。本字段目前只用于日志/Micrometer 标记,
 *       LLM 看到的 content 一视同仁(成功 / 失败都进下一轮)。</li>
 * </ul>
 */
public record ToolResult(String content, boolean isError) {

    /** 成功结果工厂方法。 */
    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    /** 失败结果工厂方法。 */
    public static ToolResult error(String content) {
        return new ToolResult(content, true);
    }
}
