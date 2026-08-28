package com.nexusforge.chat;

import com.nexusforge.ai.ChatChunk;

/**
 * ChatChunk ⇄ SSE 帧编码(无状态、纯函数)。
 *
 * <p>SSE 帧格式(每帧一行前缀 + 字段 + 一行空行):
 * <pre>
 *   event: delta
 *   id: cmpl-abc123
 *   data: {"id":"cmpl-abc123","model":"gpt-4o-mini","deltaContent":"hi"}
 *
 * </pre>
 *
 * <p>终止帧:
 * <pre>
 *   event: finish
 *   data: {"finishReason":"stop","usage":{"promptTokens":3,"completionTokens":2,"totalTokens":5}}
 *
 * </pre>
 *
 * <p>心跳帧:
 * <pre>
 *   : ping
 *
 * </pre>
 * (注释行,以冒号开头,SSE 客户端忽略,SSE 标准用于 keep-alive)。
 *
 * <p>本类只做"序列化"与"反序列化",不持有连接、订阅、线程;由上层 controller 决定何时调用。
 */
public final class SseEventCodec {

    public static final String EVENT_DELTA = "delta";
    public static final String EVENT_FINISH = "finish";
    public static final String EVENT_ERROR = "error";
    public static final String EVENT_DONE = "done";

    private SseEventCodec() {}

    /** 单 chunk 序列化为 SSE 帧字符串(末尾含 \n\n 终止)。 */
    public static String deltaFrame(ChatChunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(EVENT_DELTA).append('\n');
        if (chunk.getId() != null) sb.append("id: ").append(chunk.getId()).append('\n');
        sb.append("data: ").append(toJson(chunk)).append("\n\n");
        return sb.toString();
    }

    public static String finishFrame(ChatChunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(EVENT_FINISH).append('\n');
        sb.append("data: ").append(toJson(chunk)).append("\n\n");
        return sb.toString();
    }

    public static String errorFrame(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(EVENT_ERROR).append('\n');
        sb.append("data: ").append(message == null ? "" : message.replace("\n", "\\n")).append("\n\n");
        return sb.toString();
    }

    public static String doneFrame() {
        return "event: " + EVENT_DONE + "\ndata: [DONE]\n\n";
    }

    /** 心跳:评论行格式 ": ping\n\n"。 */
    public static String heartbeatFrame() {
        return ": ping\n\n";
    }

    /**
     * ChatChunk → JSON 字符串(用 Jackson 3.x 与 common 的对象模型一致)。
     * 简化:不注入 ObjectMapper,使用 Jackson 的静态 API;若未来要重命名字段,
     * 替换为构造 ObjectNode 再 writeValueAsString。
     */
    private static String toJson(ChatChunk chunk) {
        // 轻量手写,避免再构造一个 ObjectMapper 实例;字段顺序固定便于测试断言
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (chunk.getId() != null)        { sb.append(jsonField("id", chunk.getId(), first)); first = false; }
        if (chunk.getModel() != null)     { sb.append(jsonField("model", chunk.getModel(), first)); first = false; }
        if (chunk.getDeltaContent() != null) { sb.append(jsonField("deltaContent", chunk.getDeltaContent(), first)); first = false; }
        if (chunk.getFinishReason() != null) { sb.append(jsonField("finishReason", chunk.getFinishReason(), first)); first = false; }
        if (chunk.getUsage() != null) {
            sb.append(first ? "" : ",").append("\"usage\":{"
                    + "\"promptTokens\":" + nullSafe(chunk.getUsage().getPromptTokens())
                    + ",\"completionTokens\":" + nullSafe(chunk.getUsage().getCompletionTokens())
                    + ",\"totalTokens\":" + nullSafe(chunk.getUsage().getTotalTokens())
                    + "}");
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonField(String name, String value, boolean first) {
        return (first ? "" : ",") + "\"" + name + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String nullSafe(Integer v) { return v == null ? "0" : String.valueOf(v); }
}