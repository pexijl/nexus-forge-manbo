package com.nexusforge.ai.tools;

import com.nexusforge.client.ToolExecutor;
import com.nexusforge.client.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 内置 echo 工具 —— 把入参序列化为字符串后回显。
 *
 * <p>P4 Step 12 最小闭环的"占位工具":让端到端 demo 上"调工具 → 总结"链路可跑,
 * 不依赖任何外部服务(DB / HTTP / 文件系统)。
 *
 * <p>典型用例:用户在对话里说"用 echo 工具回显 hello",模型返回
 * {@code tool_calls=[{name:"echo", arguments:{input:"hello"}}]},本工具把
 * arguments 序列化为 {@code {"input":"hello"}} 回灌,模型下一轮收到该字符串
 * 并据此生成回答。
 *
 * <p>后续会替换为真实工具(查询 DB / HTTP / 图表 / RAG 检索);本类保留作为
 * 最小可工作的工具示例与单元测试 fixture。
 */
@Component
public class EchoTool implements ToolExecutor {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        String body = (arguments == null || arguments.isNull()) ? "null" : arguments.toString();
        return ToolResult.ok("echo: " + body);
    }
}
