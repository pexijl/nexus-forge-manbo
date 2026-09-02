package com.nexusforge.util;

import com.nexusforge.config.JwtProperties;
import com.nexusforge.enums.TokenType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具类 —— Token 签发 + 解析 + 校验。
 *
 * <p><b>角色</b>:auth 模块唯一与 jjwt 库打交道的工具类,所有 JWT 操作经此。
 *
 * <p><b>3 个公开流程</b>:
 * <ul>
 *   <li>{@link #createToken} —— 签发({@code AuthService.issueTokens} / {@code refresh} 用)</li>
 *   <li>{@link #parseToken} —— 解析 + 验签({@code JwtAuthenticationFilter} / {@code JwtQueryTokenFilter} /
 *       {@code AuthService.refresh} 用)</li>
 *   <li>{@link #validateToken} —— boolean 包装(Filter 用,无效返 false 不抛)</li>
 * </ul>
 *
 * <p><b>关键设计</b>:
 * <ul>
 *   <li>HMAC 算法由密钥长度自动决定(256-bit → HS256 / 384-bit → HS384 / 512-bit → HS512)</li>
 *   <li>用 {@link Clock} 注入代替 {@code System.currentTimeMillis()} —— 测试可控时间</li>
 *   <li>密钥从 {@link JwtProperties#getSecret()} 读 Base64 字符串,
 *       启动时 {@code @PostConstruct} 解码,<b>启动期 fail-fast</b>(密钥坏掉 → 应用起不来,
 *       不会到首个请求才报错)</li>
 *   <li>{@link #parseToken} <b>不查黑名单</b>、<b>不验 type</b>——那些由调用方负责</li>
 * </ul>
 *
 * @see com.nexusforge.config.JwtProperties 配置项(secret / ttl / header / prefix)
 * @see com.nexusforge.service.AuthService 主调方(签发 + 解析)
 * @see com.nexusforge.filter.JwtAuthenticationFilter 主调方(校验)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProps;
    private final Clock clock;
    private SecretKey secretKey;

    /**
     * Token 签发结果三件套(jti + token + 过期时间戳毫秒)。
     *
     * <p><b>为什么三件套而非只返 String token</b>:
     * <ul>
     *   <li>{@code jti} —— Redis 黑名单 key / 刷新版本号校验需要(单独返让调用方不必再 parseClaims 抽)</li>
     *   <li>{@code token} —— 完整 JWT 字符串,直接返给前端</li>
     *   <li>{@code expiresAt} —— 过期时间戳(毫秒),让 {@code AuthService.storeRefreshVersion}
     *       / {@code AuthService.blacklist} 直接 set Redis TTL,<b>不用</b>再调 {@link #remainingMillis}</li>
     * </ul>
     *
     * @param jti       JWT ID(UUID v4),用于黑名单 + 刷新版本号
     * @param token     完整 JWT 字符串(header.payload.signature 三段式,Base64Url 编码)
     * @param expiresAt 过期时间戳毫秒(用于精准设置 Redis TTL)
     */
    public record TokenPair(String jti, String token, long expiresAt) {
    }

    /**
     * 启动时解码密钥,准备 HMAC 签名的 SecretKey。
     * <p>密钥从 {@link JwtProperties#getSecret()} 读 Base64 字符串 → 解码为字节 →
     * jjwt {@code Keys.hmacShaKeyFor} 按长度自动选 HS256/384/512。
     * <p><b>启动期 fail-fast</b>:密钥格式错 / 长度不够 → 抛异常,应用起不来;
     * 不会等到首个请求才报错。
     */
    @PostConstruct
    public void init() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtProps.getSecret());
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 JWT Token（支持 access / refresh 双轨制）
     *
     * <p>生成的 Token 包含以下标准 Claims：</p>
     * <pre>
     * {
     *   "sub": "123",                // 用户 ID（字符串格式）
     *   "typ": "access" | "refresh", // Token 类型，防止 refresh token 误用于业务接口
     *   "jti": "uuid-v4",            // Token 唯一标识，用于黑名单精确吊销和刷新版本控制
     *   "username": "alice",         // 用户名（业务字段）
     *   "iat": 1720204800,           // 签发时间（秒）
     *   "exp": 1720205700            // 过期时间（秒）：access 默认 +15min，refresh 默认 +7d
     * }
     * </pre>
     *
     * <p>注意：角色（roles）<b>不放在 token 中</b>。每个请求由
     * {@link com.nexusforge.security.PermissionLoader} 从 Redis
     * (`auth:roles:{userId}`) 实时拉取，便于立即反映角色变更且避免 Token 膨胀。</p>
     *
     * @param subject 用户 ID（作为 JWT 的 subject 声明）
     * @param claims  自定义业务声明（推荐仅放 username 等展示性字段）
     * @param type    Token 类型（ACCESS / REFRESH），决定有效期长度和后续校验逻辑
     * @return TokenPair 包含：
     * <ul>
     *   <li>jti - Token 唯一标识，用于黑名单索引和版本校验</li>
     *   <li>token - 完整的 JWT 字符串</li>
     *   <li>expiresAt - 过期时间戳（毫秒），便于调用方直接设置 Redis TTL</li>
     * </ul>
     */
    public TokenPair createToken(String subject, Map<String, Object> claims, TokenType type) {
        // 生成全局唯一标识符（jti）作为 Token 的唯一指纹
        String jti = UUID.randomUUID().toString();
        // 使用注入的 Clock 获取当前时间戳
        long now = clock.millis();
        // 根据 Token 类型（access/refresh）选择对应的有效期配置
        long ttl = (type == TokenType.ACCESS) ? jwtProps.getAccessTtl() : jwtProps.getRefreshTtl();
        // 构建 JWT Token
        String token = Jwts.builder()
                // 设置 JWT ID（唯一标识，用于黑名单和版本控制）
                .id(jti)
                // 设置主题（存储 userId）
                .subject(subject)
                // 设置自定义业务声明（当前仅 username；角色走 Redis，见上文）
                .claims(claims)
                // 设置 Token 类型标识（access/refresh，防止误用）
                .claim("typ", type.name().toLowerCase())
                // 设置签发时间
                .issuedAt(new Date(now))
                // 设置过期时间（根据 Token 类型动态计算）
                .expiration(new Date(now + ttl))
                // 使用 HMAC 密钥签名（保证 Token 不可篡改）
                .signWith(secretKey)
                // 压缩为最终 JWT 字符串（Base64Url 编码的三段式）
                .compact();
        // 返回包含 jti、JWT 字符串和过期时间戳的 TokenPair 对象
        return new TokenPair(jti, token, now + ttl);
    }

    /**
     * 解析 JWT(同时验签 + 验过期)。
     *
     * <p><b>不查黑名单</b>:黑名单由调用方(AuthService / Filter)用 jti 自己查;
     * <b>不验 type</b>:typ 校验由 JwtAuthenticationFilter 显式调 {@link #extractType} 判。
     *
     * <p>异常:无效 token(签名错 / 篡改 / 格式错 / 已过期)抛 {@link JwtException},
     * 调用方需 catch 或转抛(AuthService 转 AuthException,Filter 透传"未认证")。
     *
     * @param token JWT 字符串(格式:header.payload.signature)
     * @return 解析后的 Claims 载荷,含 subject / 自定义字段 / iat / exp
     * @throws JwtException 签名错 / 篡改 / 过期 / 格式错
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)      // 验签(防篡改)
                .build()                     // 构建 parser
                .parseSignedClaims(token)    // 解析 + 验签 + 验过期
                .getPayload();               // 取 Claims
    }

    /**
     * 校验 token 是否有效(boolean 包装,无效不抛)。
     *
     * <p>给 {@code JwtAuthenticationFilter} / {@code JwtQueryTokenFilter} 用——它们要的是
     * "是否放行"的判断,不是异常。catch {@link JwtException}(签名错/过期/篡改)+
     * {@link IllegalArgumentException}(null / 空 token)。
     *
     * @param token JWT 字符串
     * @return true = 签名正确 + 未过期;false = 任何异常
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 Claims 读 jti(Redis 黑名单 key 用)。
     * <p>单独写方法是为统一调用入口,未来若加 jti 缓存 / 降级可在此收口。
     */
    public String extractJti(Claims claims) {
        return claims.getId();
    }

    /**
     * 读 typ claim → {@link TokenType}。
     * <p><b>缺省视为 ACCESS</b>(向前兼容老 token):typ claim 在某个版本后引入,
     * 老 token 没 typ 字段,默认 ACCESS 让老 token 仍能进业务接口;
     * <b>代价</b>:typ 缺失的 refresh token 也会被当成 access——但 refresh JTI 仍会被
     * 踢 token 流程用到,影响有限;若发现兼容性问题可加版本号字段判。
     */
    public TokenType extractType(Claims claims) {
        String typ = claims.get("typ", String.class);
        return "refresh".equalsIgnoreCase(typ) ? TokenType.REFRESH : TokenType.ACCESS;
    }

    /**
     * 算 token 剩余有效期(毫秒,≤0 = 已过期)。
     * <p>用注入的 {@link Clock}(非 {@code System.currentTimeMillis()}):测试可注入 FixedClock 验证边界。
     * <p>调用方:{@code AuthService.blacklist} 用此值设 Redis TTL(精准,不浪费)。
     */
    public long remainingMillis(Claims claims) {
        long exp = claims.getExpiration().getTime();
        return exp - clock.millis();
    }
}
