package com.nexusforge.stream;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.chat.SseEventCodec;

/**
 * ChatChunk → SSE 帧字符串(委托给 common 的 SseEventCodec)。
 *
 * <p>本类放在 ai 模块是因为它在 controller / interceptor 中用到,
 * common 的 SseEventCodec 不应反向依赖 ai 模块,所以这里搞一个小门面。
 */
public final class SseFormat {
    private SseFormat() {}

    public static String delta(ChatChunk chunk) { return SseEventCodec.deltaFrame(chunk); }
    public static String finish(ChatChunk chunk) { return SseEventCodec.finishFrame(chunk); }
    public static String error(String message)  { return SseEventCodec.errorFrame(message); }
    public static String done()                 { return SseEventCodec.doneFrame(); }
    public static String heartbeat()            { return SseEventCodec.heartbeatFrame(); }
}