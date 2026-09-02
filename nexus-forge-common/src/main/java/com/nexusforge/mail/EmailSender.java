package com.nexusforge.mail;

/**
 * 邮件发送抽象 —— 业务侧依赖此接口,不直接耦合 SMTP 实现。
 *
 * <p>提供两种实现:</p>
 * <ul>
 *   <li>{@code LoggingEmailSender} —— dev / test 落 {@code build/dev-mail/} + log,
 *       避免引入外部 SMTP 容器依赖</li>
 *   <li>{@code SmtpEmailSender} —— prod 走真实 SMTP,读 {@code spring.mail.*}</li>
 * </ul>
 *
 * <p>实现需为线程安全;实现不应抛出底层邮件异常(超时 / SMTP 错误),
 * 异常应在内部 log + 静默吞掉,业务侧假设"邮件尽力送达"——避免攻击者
 * 通过错误码嗅探邮箱存在性(密码重置场景)。</p>
 */
public interface EmailSender {

    /**
     * 发送纯文本邮件
     *
     * @param to      收件人邮箱(单一地址)
     * @param subject 主题
     * @param body    邮件正文(纯文本 / HTML;实现自行决定如何处理)
     */
    void send(String to, String subject, String body);
}
