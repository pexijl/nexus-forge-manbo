package com.nexusforge.user.notification;

/**
 * 账号注销邮件服务接口 —— 由 {@link LoggingUserDeletionMailer} /
 * {@link SmtpUserDeletionMailer} 两个实现,业务侧依赖此接口。
 */
public interface UserDeletionMailer {

    /** 发"确认注销"邮件(含 6 位验证码) */
    void sendDeleteConfirmation(String to, String code, int ttlMinutes);

    /**
     * 发"已注销"通知邮件,含一次性恢复 token
     *
     * @param to          收件人邮箱(原邮箱,PII 已擦除但邮箱本身是真实可触达的)
     * @param restoreUrl  完整恢复 URL,模板直接渲染
     */
    void sendDeletedNotice(String to, String restoreUrl);
}
