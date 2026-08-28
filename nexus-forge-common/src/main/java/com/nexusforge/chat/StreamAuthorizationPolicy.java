package com.nexusforge.chat;

/**
 * SSE 鉴权策略 —— 流式连接需要可中断特性,因此除 header 之外允许 query 携带 token,
 * 以适配浏览器 EventSource / fetch reader 中 axios 不便配置 header 的场景。
 *
 * <p>当前 P2 主路线仍然是 Authorization: Bearer header;query token 是 SSE 专用 fallback。
 */
public final class StreamAuthorizationPolicy {
    private StreamAuthorizationPolicy() {}

    /** 是否允许 query token。 */
    public static final boolean ALLOW_QUERY_TOKEN = true;

    /** query 参数名。 */
    public static final String QUERY_PARAM = "access_token";
}