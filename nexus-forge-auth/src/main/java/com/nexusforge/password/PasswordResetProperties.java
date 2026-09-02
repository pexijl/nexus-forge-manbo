package com.nexusforge.password;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 密码重置(邮箱验证码)配置 —— 绑定 {@code password-reset.*} 配置段。
 *
 * <p>默认值是 dev / 集成测试场景的合理值;生产通过环境变量覆盖(见
 * {@code application-prod.yaml})。{@code codeTtlSeconds} 在测试里可被
 * 缩短以验证"验证码过期"分支。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "password-reset")
public class PasswordResetProperties {

    /** 验证码有效期(秒),默认 300 = 5 分钟 */
    private Integer codeTtlSeconds = 300;

    /** 单邮箱最大验证失败次数,超过后验证码自动失效,默认 5 */
    private Integer maxAttempts = 5;

    /** 邮箱维度限流窗口(秒),默认 60 */
    private Integer rateLimitWindowSeconds = 60;

    /** 邮箱维度限流窗口内允许的最大发送次数,默认 1(60s 内只发 1 次) */
    private Integer rateLimitEmailMax = 1;

    /** IP 维度限流窗口内允许的最大发送次数,默认 3(60s 内同 IP 最多 3 次) */
    private Integer rateLimitIpMax = 3;
}
