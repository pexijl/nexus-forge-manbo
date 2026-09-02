package com.nexusforge.dto;

import com.nexusforge.util.JwtUtil.TokenPair;

/**
 * 登录 / 刷新接口返回的令牌包(access + refresh 双轨)。
 *
 * <p><b>双轨制</b>:{@link #access} 走业务接口鉴权(refresh 不可);{@link #refresh} 仅用于
 * {@code POST /api/auth/refresh} 换新 token,且一次性轮换,旧 refresh 立即失效。
 *
 * <p><b>type claim</b>:每个 token 内部带 {@code typ: "access"|"refresh"} 声明,
 * {@code JwtAuthenticationFilter} 会拒绝"用 refresh token 访问业务接口"的尝试——这是双轨制的安全基础。
 *
 * <p>每轨是 {@link TokenPair}({@code jti} + {@code token} + {@code expiresAt} 毫秒);
 * 前端用 {@code expiresAt} 计算"距过期多久"以决定何时静默调 /refresh 换新。
 *
 * <p><b>前端用法</b>:通常存 Pinia auth store(经 AES 持久化);每次发请求前
 * 拦截器检查 {@code access.expiresAt - now < 阈值} → 静默调 /refresh 换新。
 *
 * @param access  访问令牌(短寿命,≤15min),用于 {@code Authorization: Bearer <token>}
 * @param refresh 刷新令牌(长寿命,7d 默认,具体由 {@code jwt.refresh-ttl} 配置),
 *                仅用于 {@code POST /api/auth/refresh}
 * @see com.nexusforge.service.AuthService#issueTokens 登录时签发
 * @see com.nexusforge.service.AuthService#refresh   刷新时轮换
 * @see com.nexusforge.util.JwtUtil#createToken      实际 JWT 构造(底层)
 * @see com.nexusforge.util.JwtUtil.TokenPair        access/refresh 内部的 jti/token/expiresAt
 */
public record TokenBundle(

        /** 访问令牌,用于业务接口鉴权;短寿命(≤15min),过期前用 {@link #refresh} 换新 */
        TokenPair access,
        /** 刷新令牌,仅用于 {@code POST /api/auth/refresh};长寿命(7d 默认),一次性轮换,旧 refresh 立即失效 */
        TokenPair refresh
) {
}
