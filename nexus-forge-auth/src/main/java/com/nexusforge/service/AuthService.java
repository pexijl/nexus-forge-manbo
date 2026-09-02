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
 * 认证服务 —— 负责 Token 签发、刷新、登出 + Redis 状态管理。
 *
 * <p><b>3 个公开流程</b>:
 * <ul>
 *   <li>{@link #issueTokens} —— 登录后签发 access + refresh(单点登录实现)</li>
 *   <li>{@link #refresh} —— 用旧 refresh 换新 access + refresh(一次性轮换)</li>
 *   <li>{@link #logout} / {@link #logoutAllRefreshTokens} —— 撤销 access / 全部 refresh</li>
 * </ul>
 *
 * <p><b>协作方</b>:
 * <ul>
 *   <li>{@code AuthController} 三个端点(login / refresh / logout)都调本类</li>
 *   <li>{@code JwtAuthenticationFilter} 每个请求调 {@link #isBlacklisted} 查黑名单</li>
 *   <li>{@code AuthEventListener} 消费 {@code UserBannedEvent} 调 {@link #logoutAllRefreshTokens}</li>
 * </ul>
 *
 * <p><b>Redis 状态管理</b>(2 个 key 空间):
 * <ul>
 *   <li>黑名单: {@code auth:blacklist:{jti}} → "1"(TTL = Token 剩余有效期,精准吊销)</li>
 *   <li>版本号: {@code auth:refresh:{userId}} → "{jti}"(TTL = refresh 剩余有效期,
 *       防重放 + 单点登录 + 踢下线)</li>
 * </ul>
 *
 * <p><b>设计原则</b>:
 * <ul>
 *   <li><b>access 不主动撤销</b>:access 是无状态 JWT,签发后无法在 token 层面撤销,
 *       依赖 ≤15min 自然过期;refresh 通过 Redis 状态精准撤销</li>
 *   <li><b>单点登录</b>:每次 issueTokens 覆盖 {@code auth:refresh:{userId}},
 *       旧 refresh 下次刷新时因 jti 不匹配而失效</li>
 *   <li><b>Token 最小化</b>:claims 只放 username(展示字段),角色每次请求从 Redis 拉
 *       (见 {@code com.nexusforge.security.PermissionLoader})</li>
 *   <li><b>双重保险</b>:黑名单 + 版本号同时校验,任何一边丢失另一边兜底</li>
 * </ul>
 *
 * @see com.nexusforge.controller.AuthController 调用方
 * @see com.nexusforge.filter.JwtAuthenticationFilter 黑名单查询方
 * @see com.nexusforge.listener.AuthEventListener 踢下线触发方
 * @see com.nexusforge.util.JwtUtil JWT 构造 + 解析
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
        // 1) 构造 claims(只放 username,见 buildClaims Javadoc)
        Map<String, Object> claims = buildClaims(user);

        // 2) 签发双轨:同 userId 同样 claims,仅 type 不同(让 JwtAuthenticationFilter 拒 refresh 进业务接口)
        TokenPair access = jwtUtil.createToken(String.valueOf(user.getUserId()), claims, TokenType.ACCESS);
        TokenPair refresh = jwtUtil.createToken(String.valueOf(user.getUserId()), claims, TokenType.REFRESH);

        // 3) 注册 refresh 版本号:覆盖 auth:refresh:{userId} → 实现"单点登录"
        //    (旧 refresh 下次刷新时 jti 不匹配 → 抛 TOKEN_VERSION_MISMATCH)
        storeRefreshVersion(user.getUserId(), refresh.jti(), refresh.expiresAt());

        return new TokenBundle(access, refresh);
    }

    /**
     * 刷新：用旧 refresh 换新 access + 新 refresh
     */
    public TokenBundle refresh(String refreshToken) {
        // 1) 验签:JwtUtil 内部验签名 + 过期 + issuer;失败抛 JwtException(冒泡到 GlobalExceptionHandler)
        Claims claims = jwtUtil.parseToken(refreshToken);

        // 2) type 校验:必须是 refresh,access 一律拒绝(防 access 重复用)
        if (jwtUtil.extractType(claims) != TokenType.REFRESH) {
            throw new AuthException(ResultCode.INVALID_TOKEN_TYPE);
        }

        // 3) 黑名单校验:被踢过的 refresh 不能换新(防被偷的 token 复用)
        if (isBlacklisted(claims)) {
            throw new AuthException(ResultCode.TOKEN_REVOKED);
        }

        // 4) 版本号校验:Redis 存的 auth:refresh:{userId} == 当前 token 的 jti?
        //    不等 → 期间有过新登录(单点登录踢旧)或 logoutAllRefreshTokens(被封禁)
        Long userId = Long.valueOf(claims.getSubject());
        String currentJti = redis.opsForValue().get(jwtProps.getRefreshPrefix() + userId);
        if (currentJti == null || !currentJti.equals(claims.getId())) {
            throw new AuthException(ResultCode.TOKEN_VERSION_MISMATCH);
        }

        // 5) 把旧 refresh jti 加黑名单:即使 Redis 状态丢了(版本号被删),旧 refresh 也不能换新
        //    双重保险:黑名单 + 版本号同时校验,任何一边失效另一边兜底
        blacklist(claims);

        // 6) 重新读取用户信息(不沿用旧 LoginUser):让角色变更后能立即生效;
        //    ⚠️ loadById 找不到时抛 UsernameNotFoundException,会冒泡到 GlobalExceptionHandler
        //    兜底(见 UserLoader.loadById Javadoc 改进点)
        LoginUser user = userLoader.loadById(userId);

        // 7) 重新签发双轨 + 覆盖版本号,同 issueTokens 流程
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
        // 1) access 进黑名单(按剩余 TTL 精准加,见 blacklist 注释)
        //    ⚠️ 改进点:parseToken 可能抛 JwtException(token 损坏 / 伪造 / 过期),
        //    目前会冒泡到 GlobalExceptionHandler 兜底 500;理想行为应 catch + 返"已登出"
        if (accessToken != null) {
            Claims ac = jwtUtil.parseToken(accessToken);
            blacklist(ac);
        }
        if (refreshTokenOrNull != null) {
            // 2) refresh 进黑名单 + 删版本号:双重保险让历史 refresh 全部失效
            //    即便黑名单 Redis 故障丢了,版本号删除也能让旧 refresh 换新时失败
            Claims rc = jwtUtil.parseToken(refreshTokenOrNull);
            blacklist(rc);
            redis.delete(jwtProps.getRefreshPrefix() + rc.getSubject());
        }
    }

    // ---------- 内部方法 ----------

    /**
     * 把 token jti 加黑名单,TTL = token 剩余有效期(精准,不浪费 Redis 内存)。
     * <p>已过期 token(ttl ≤ 0)直接跳过:已经在自然失效,无需加黑名单。
     */
    private void blacklist(Claims claims) {
        long ttl = jwtUtil.remainingMillis(claims);
        if (ttl <= 0) return;  // 已过期,无需吊销
        redis.opsForValue().set(
                jwtProps.getBlacklistPrefix() + claims.getId(),
                "1",
                Duration.ofMillis(ttl)
        );
    }

    /**
     * 判断 token jti 是否在黑名单。<b>公开方法</b>给 {@code JwtAuthenticationFilter} 用。
     * <p>{@code Boolean.TRUE.equals(...)} 是 null-safe 写法:Redis 抛 null 时返 false。
     * 用 {@code hasKey} 而非 {@code get}:只需要判断存在性,省一次反序列化。
     */
    public boolean isBlacklisted(Claims claims) {
        return Boolean.TRUE.equals(
                redis.hasKey(jwtProps.getBlacklistPrefix() + claims.getId())
        );
    }

    /**
     * 存 refresh 版本号 → 实现单点登录 + 踢下线。
     * <p>同 userId 新登录会覆盖此 key,旧 refresh 下次刷新时 jti 不匹配 → 失效。
     * TTL = refresh 剩余有效期,refresh 自然过期后自动清理(无需主动删)。
     */
    private void storeRefreshVersion(Long userId, String jti, long expiresAt) {
        long ttlMs = expiresAt - System.currentTimeMillis();
        if (ttlMs <= 0) return;  // refresh 已过期,无需存
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
