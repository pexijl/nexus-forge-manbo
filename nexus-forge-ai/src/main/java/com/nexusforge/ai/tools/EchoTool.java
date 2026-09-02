package com.nexusforge.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 内置 echo 工具 —— 把入参序列化为字符串后回显。
 *
 * <p>spring-ai-full-migration Phase 3 — 改用 Spring AI 的 {@link Tool @Tool}
 * 注解替代自实现的 {@code ToolExecutor} SPI:
 * <ul>
 *   <li>方法级注解让 Spring AI 自动反射,无需自己写 JSON schema 生成逻辑</li>
 *   <li>返回 {@link String} 即可(Spring AI 内部用 {@code ToolCallResultConverter}
 *       序列化为 JSON)</li>
 *   <li>{@link ToolParam} 描述参数,LLM 读 description 知道传什么</li>
 * </ul>
 *
 * <p>典型用例:用户对话里说"用 echo 工具回显 hello",LLM 返回
 * {@code tool_calls=[{name:"echo", arguments:{input:"hello"}}]},本工具把
 * arguments 拼成字符串回灌,LLM 下一轮收到该字符串并据此生成回答。
 *
 * <p>本类作为最小可工作的工具示例 + 单元测试 fixture 保留。真实业务工具
 * (DB 查询 / HTTP / 图表 / RAG 检索)按同样模式加 — 每个 @Component
 * 里的 @Tool 方法都自动被 {@code MethodToolCallbackProvider} 扫描进
 * 工具池。
 */
@Component
public class EchoTool {

    @Tool(description = "回显入参的字符串表示;用于 LLM 测试工具调用回路是否打通,无副作用。")
    public String echo(@ToolParam(description = "要回显的内容(任意字符串)") String input) {
        return "echo: " + input;
    }
}
