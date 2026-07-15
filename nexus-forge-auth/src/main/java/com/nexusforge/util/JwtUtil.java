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
 * JWT 工具类，提供生成和解析 JWT 的方法
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProps;
    private final Clock clock;
    private SecretKey secretKey;

    /**
     * 返回 [jti, token, expiresAtMillis]
     */
    public record TokenPair(String jti, String token, long expiresAt) {
    }

    @PostConstruct
    public void init() {
        // 从配置中获取 Base64 编码的密钥，并解码为字节数组
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
     *         <ul>
     *           <li>jti - Token 唯一标识，用于黑名单索引和版本校验</li>
     *           <li>token - 完整的 JWT 字符串</li>
     *           <li>expiresAt - 过期时间戳（毫秒），便于调用方直接设置 Redis TTL</li>
     *         </ul>
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
     * 解析并验证 JWT Token
     *
     * @param token JWT 字符串（格式：header.payload.signature）
     * @return 解析后的 Claims 载荷，包含 subject、自定义字段及时间信息
     * @throws JwtException token 无效、过期或签名验证失败时抛出
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                // 用密钥验证签名，确保 token 未被篡改
                .verifyWith(secretKey)
                // 构建解析器
                .build()
                // 解析 token，同时验签 + 检查过期时间
                .parseSignedClaims(token)
                // 获取 Payload 部分（即 Claims 数据）
                .getPayload();
    }

    /**
     * 校验 Token 是否有效
     *
     * @param token JWT 字符串
     * @return true-有效（签名正确且未过期），false-无效或已过期
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

    /** 从 Claims 读 jti（黑名单查询用） */
    public String extractJti(Claims claims) {
        return claims.getId();
    }

    /** 读取 token 类型 claim，缺省视为 access（向前兼容老 token） */
    public TokenType extractType(Claims claims) {
        String typ = claims.get("typ", String.class);
        return "refresh".equalsIgnoreCase(typ) ? TokenType.REFRESH : TokenType.ACCESS;
    }

    /** 计算 token 剩余有效期（毫秒），<=0 表示已过期 */
    public long remainingMillis(Claims claims) {
        long exp = claims.getExpiration().getTime();
        return exp - clock.millis();
    }
}
