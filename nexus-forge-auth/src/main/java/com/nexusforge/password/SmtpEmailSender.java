package com.nexusforge.password;

import com.nexusforge.mail.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * prod SMTP 邮件发送器 —— 走 Spring Boot 邮件 starter 提供的 {@link JavaMailSender},
 * 主机 / 端口 / 用户名 / 密码从 {@code spring.mail.*} 读。
 *
 * <p>启用条件:仅当 {@code mail.mode=smtp} 时注册。{@link LoggingEmailSender} 同样
 * 实现 {@link EmailSender} SPI,Spring 会按条件二选一(不会冲突)。</p>
 *
 * <p><b>安全/隐私</b>:log 只打主题 / 收件人打码,不打印正文;正文可能含验证码。
 * 异常仅 log,业务侧不感知 —— 密码重置场景"邮件尽力送达"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mail.mode", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;

    @Value("${mail.from:Nexus Forge <no-reply@nexus-forge.local>}")
    private String from;

    @Override
    public void send(String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("[smtp-mail] sent to={} subject=\"{}\"", LoggingEmailSender.maskEmail(to), subject);
        } catch (Exception e) {
            // 邮件"尽力送达"原则 —— 异常仅 log,业务侧假设已发送
            log.warn("[smtp-mail] failed to send to={}: {}", LoggingEmailSender.maskEmail(to), e.getMessage());
        }
    }
}
