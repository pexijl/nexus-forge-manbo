package com.nexusforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置类，用于加载 JWT 相关的配置项，例如签名密钥和过期时间
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** Base64 编码的 HMAC 密钥（>=32 字节） */
    private String secret;

    /** access token 有效期（毫秒），默认 15 分钟 */
    private Long accessTtl;

    /** refresh token 有效期（毫秒），默认 7 天 */
    private Long refreshTtl;

    /**
     * JWT 请求头名称
     */
    private String header;

    /**
     * JWT 前缀，通常为 "Bearer "，用于从请求头中提取 Token
     */
    private String prefix;

    /** 是否启用 Redis 黑名单；默认 true */
    private Boolean enableBlacklist = Boolean.TRUE;

    /** 黑名单 key 前缀 */
    private String blacklistPrefix = "auth:blacklist:";

    /** refresh token 存储前缀（白名单/版本号机制） */
    private String refreshPrefix = "auth:refresh:";
}
