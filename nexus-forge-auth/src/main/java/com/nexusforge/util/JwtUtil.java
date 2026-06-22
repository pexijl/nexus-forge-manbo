package com.nexusforge.util;

import com.nexusforge.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类，提供生成和解析 JWT 的方法
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProps;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        // 从配置中获取 Base64 编码的密钥，并解码为字节数组
        byte[] keyBytes = Base64.getDecoder().decode(jwtProps.getSecret());
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 JWT Token
     * <p>
     * 使用 HS256 算法签名，包含签发时间、过期时间和自定义载荷。
     * 过期时间由配置项 {@code jwt.ttl} 控制。
     *
     * @param subject 主题，建议传入用户唯一标识（如 userId）
     * @param claims  自定义声明，如 username、role 等
     * @return JWT 字符串，格式：header.payload.signature
     * @see io.jsonwebtoken.Jwts
     */
    public String createToken(String subject, Map<String, Object> claims) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                // sub: 主题，标识 token 归属（如用户ID）
                .subject(subject)
                // 自定义载荷（如 username、role 等）
                .claims(claims)
                // iat: 签发时间，用于计算 token 存活时长
                .issuedAt(new Date(now))
                // exp: 过期时间 = 当前时间 + 配置的有效期
                .expiration(new Date(now + jwtProps.getTtl()))
                // 用密钥签名，防止 token 被篡改
                .signWith(secretKey)
                // 组装成 "header.payload.signature" 字符串
                .compact();
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
}
