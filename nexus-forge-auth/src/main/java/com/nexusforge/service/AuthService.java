package com.nexusforge.service;

import com.nexusforge.config.JwtProperties;
import com.nexusforge.dto.TokenBundle;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.enums.TokenType;
import com.nexusforge.exception.AuthException;
import com.nexusforge.security.LoginUser;
import com.nexusforge.security.UserLoader;
import com.nexusforge.util.JwtUtil;
import com.nexusforge.util.JwtUtil.TokenPair;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务：负责 Token 签发、刷新和登出
 *
 * <p>Redis 状态管理：</p>
 * <ul>
 *   <li>黑名单：auth:blacklist:{jti} -> "1"（TTL = Token 剩余有效期，用于精确吊销）</li>
 *   <li>版本号：auth:refresh:{userId} -> "{jti}"（TTL = refresh 剩余有效期，用于防重放和踢下线）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProps;
    private final StringRedisTemplate redis;
    private final UserLoader userLoader;

    /**
     * 登录成功 —— 返回 access + refresh
     *
     * <p>Token 仅承载 sub(用户ID)/typ/jti/username/iat/exp；角色由
     * {@link com.nexusforge.security.PermissionLoader} 每次请求从 Redis 读取，
     * 不放在 token 中以避免 Token 膨胀及刷新前拿不到最新角色的问题。</p>
     */
    public TokenBundle issueTokens(LoginUser user) {
        Map<String, Object> claims = buildClaims(user);

        TokenPair access = jwtUtil.createToken(String.valueOf(user.getUserId()), claims, TokenType.ACCESS);
        TokenPair refresh = jwtUtil.createToken(String.valueOf(user.getUserId()), claims, TokenType.REFRESH);

        // 注册当前 refresh 版本号（覆盖旧的，实现“单点登录”）
        storeRefreshVersion(user.getUserId(), refresh.jti(), refresh.expiresAt());

        return new TokenBundle(access, refresh);
    }

    /**
     * 刷新：用旧 refresh 换新 access + 新 refresh
     */
    public TokenBundle refresh(String refreshToken) {
        Claims claims = jwtUtil.parseToken(refreshToken);

        if (jwtUtil.extractType(claims) != TokenType.REFRESH) {
            // 非 refresh token，拒绝刷新
            throw new AuthException(ResultCode.INVALID_TOKEN_TYPE);
        }
        if (isBlacklisted(claims)) {
            // refresh token 已被吊销
            throw new AuthException(ResultCode.TOKEN_REVOKED);
        }

        Long userId = Long.valueOf(claims.getSubject());

        // 版本号校验：Redis 里存的 jti 必须 == 当前 token 的 jti
        String currentJti = redis.opsForValue().get(jwtProps.getRefreshPrefix() + userId);
        if (currentJti == null || !currentJti.equals(claims.getId())) {
            // refresh token 已失效（版本不一致）
            throw new AuthException(ResultCode.TOKEN_VERSION_MISMATCH);
        }

        // 重新签发，同时把旧 refresh jti 加入黑名单（避免被复用）
        blacklist(claims);

        // 重新读取用户信息（角色变更后能立即生效）
        LoginUser user = userLoader.loadById(userId);

        Map<String, Object> claimsMap = buildClaims(user);

        TokenPair access = jwtUtil.createToken(String.valueOf(userId), claimsMap, TokenType.ACCESS);
        TokenPair refresh = jwtUtil.createToken(String.valueOf(userId), claimsMap, TokenType.REFRESH);
        storeRefreshVersion(userId, refresh.jti(), refresh.expiresAt());

        return new TokenBundle(access, refresh);
    }

    /**
     * 登出：吊销当前 access + 全部 refresh
     */
    public void logout(String accessToken, String refreshTokenOrNull) {
        if (accessToken != null) {
            Claims ac = jwtUtil.parseToken(accessToken);
            blacklist(ac);
        }
        if (refreshTokenOrNull != null) {
            Claims rc = jwtUtil.parseToken(refreshTokenOrNull);
            blacklist(rc);
            // 同时清掉版本号，让历史 refresh 全部失效
            redis.delete(jwtProps.getRefreshPrefix() + rc.getSubject());
        }
    }

    // ---------- 内部方法 ----------

    private void blacklist(Claims claims) {
        long ttl = jwtUtil.remainingMillis(claims);
        if (ttl <= 0) return;  // 已过期，无需吊销
        redis.opsForValue().set(
                jwtProps.getBlacklistPrefix() + claims.getId(),
                "1",
                Duration.ofMillis(ttl)
        );
    }

    public boolean isBlacklisted(Claims claims) {
        return Boolean.TRUE.equals(
                redis.hasKey(jwtProps.getBlacklistPrefix() + claims.getId())
        );
    }

    private void storeRefreshVersion(Long userId, String jti, long expiresAt) {
        long ttlMs = expiresAt - System.currentTimeMillis();
        if (ttlMs <= 0) return;
        redis.opsForValue().set(
                jwtProps.getRefreshPrefix() + userId,
                jti,
                Duration.ofMillis(ttlMs)
        );
    }

    /**
     * 构造写入 JWT payload 的业务 claims。
     *
     * <p>这里只放"轻量、不易变"的展示性字段（如 username）。角色经
     * {@link com.nexusforge.security.PermissionLoader} 从 Redis 实时拉取，
     * 既能立即反映角色变更，也避免 Token 膨胀。</p>
     */
    private Map<String, Object> buildClaims(LoginUser user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", user.getUsername());
        return claims;
    }

    /**
     * 吊销用户的所有 Refresh Token（用于封禁/强制踢下线）
     *
     * <p>实现原理：删除 Redis 中的版本号 key，使所有历史 refresh token 在刷新时因版本不匹配而失效。</p>
     *
     * <p>注意：此方法不会吊销已发出的 Access Token，它们将在剩余 TTL（默认 15 分钟）内自然过期。</p>
     * <p>如需立即失效所有 Access Token，需额外维护 access token 列表（暂不实现）。</p>
     *
     * @param userId 用户 ID
     */
    public void logoutAllRefreshTokens(Long userId) {
        if (userId == null) {
            log.warn("注销 Refresh Token 失败：userId 为 null");
            return;
        }

        String key = jwtProps.getRefreshPrefix() + userId;
        Boolean deleted = redis.delete(key);

        if (Boolean.TRUE.equals(deleted)) {
            log.info("已吊销用户 {} 的所有 Refresh Token", userId);
        } else {
            log.debug("用户 {} 无活跃 Refresh Token", userId);
        }
    }
}
