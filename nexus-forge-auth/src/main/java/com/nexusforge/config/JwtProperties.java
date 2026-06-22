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

    /**
     * JWT 签名密钥, Base64编码
     */
    private String secret;

    /**
     * JWT 过期时间，单位为毫秒
     */
    private Long ttl;

    /**
     * JWT 请求头名称
     */
    private String header;

    /**
     * JWT 前缀，通常为 "Bearer "，用于从请求头中提取 Token
     */
    private String prefix;
}
