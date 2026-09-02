package com.nexusforge.user;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 账号生命周期配置 —— 绑定 {@code account-lifecycle.*} 配置段。
 *
 * <p>默认值是 dev / 集成测试场景的合理值;生产通过环境变量覆盖。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "account-lifecycle")
public class AccountLifecycleProperties {

    /** 注销后宽限期(天),过期后定时任务真删。默认 7 天,跟 GitHub 一致。 */
    private Integer deletionGracePeriodDays = 7;

    /** 注销确认邮件验证码有效期(分钟),默认 5。 */
    private Integer deleteCodeTtlMinutes = 5;

    /** 注销验证码失败次数上限,默认 5 —— 同密码重置。 */
    private Integer deleteCodeMaxAttempts = 5;

    /** 恢复 token 有效期(天),默认 14 —— 比 grace period 长几天,留余量。 */
    private Integer restoreTokenTtlDays = 14;

    /** expire-deletions 定时任务 cron 表达式,默认每天凌晨 3 点。 */
    private String expireDeletionsCron = "0 0 3 * * *";

    /**
     * 邮件中恢复链接的 baseUrl(默认 localhost dev)。
     * 生产通过 {@code APP_BASE_URL} 环境变量覆盖。
     */
    private String restoreBaseUrl = "http://localhost:5173";
}

