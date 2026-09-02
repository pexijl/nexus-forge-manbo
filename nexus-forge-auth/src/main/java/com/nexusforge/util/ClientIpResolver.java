package com.nexusforge.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析 —— 按 "X-Forwarded-For 优先,回退 X-Real-IP,再回退 remoteAddr" 的标准顺序。
 *
 * <p><b>为什么需要</b>:反代(nginx / SLB / Cloudflare)后面部署时,
 * {@link HttpServletRequest#getRemoteAddr()} 拿到的是反代 IP,不是真实客户端;
 * XFF / X-Real-IP 由反代附加,携带真实客户端 IP。密码重置等"按 IP 限流"场景必须用真实 IP。
 *
 * <p><b>解析顺序</b>:
 * <ol>
 *   <li>{@code X-Forwarded-For} 头(取最左非空 IP——追加顺序是 "client, proxy1, proxy2")</li>
 *   <li>{@code X-Real-IP} 头(nginx 等反代常用)</li>
 *   <li>{@link HttpServletRequest#getRemoteAddr()} 兜底</li>
 * </ol>
 *
 * <p><b>⚠️ IP 伪造风险</b>:直接信任 XFF 在反代未配置时存在伪造风险;
 * 生产环境建议让反代<b>剥离客户端 XFF 并显式附加自己的 XFF</b>(nginx 用
 * {@code proxy_set_header X-Forwarded-For $remote_addr}),这里仅做"尽力解析"。
 * 若需要严格白名单,可在此方法加 trusted-proxy 校验(参考 Spring 的
 * {@code ForwardedHeaderFilter})。
 *
 * <p><b>唯一调用方</b>:{@code AuthController.requestPasswordReset} 在密码重置限流时用——
 * 防邮件炸弹(同一 IP 频繁触发验证码发送)。
 *
 * @see com.nexusforge.controller.AuthController#requestPasswordReset 调用方
 * @see com.nexusforge.password.PasswordResetService 限流逻辑
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    /**
     * 解析客户端真实 IP。
     *
     * <p>优先 XFF(取最左),其次 X-Real-IP,再次 {@code remoteAddr} 兜底;
     * 全部拿不到时返 {@code "unknown"} 字面量(而非 null,避免调用方 NPE)。
     *
     * <p><b>"unknown" 兜底的设计</b>:返字面量字符串让所有调用方都不需要 null 检查;
     * 限流 key 用 IP 时,"unknown" 会聚到同一桶,不影响安全(最多让匿名请求
     * 共享同一限流配额,实际攻击者也用不了)。
     *
     * @param request HTTP 请求;允许为 null(返 "unknown")
     * @return 客户端 IP 字符串;无法解析时返 {@code "unknown"}
     */
    public static String resolve(HttpServletRequest request) {
        // 防御:request 为 null(测试场景)返字面量 "unknown" 避免 NPE
        if (request == null) return "unknown";

        // 1) XFF 头(标准做法):"client, proxy1, proxy2" 追加,最左是原始客户端
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // indexOf(',') > 0:单 IP 时返 -1,这时用整个 xff;多 IP 时切最左 trim
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) return first;
        }

        // 2) X-Real-IP(nginx 反代常用,无逗号):直接 trim
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        // 3) remoteAddr 兜底(Servlet 容器看到的对端 IP,反代场景下是反代 IP)
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
