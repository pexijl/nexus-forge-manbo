package com.nexusforge.user.notification;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * 账号注销邮件的 SMTP 实现 —— 走 spring-boot-starter-mail 提供的 {@link JavaMailSender}。
 *
 * <p>启用条件:仅当 {@code mail.mode=smtp} 时注册。{@link LoggingUserDeletionMailer}
 * 同样实现 {@link UserDeletionMailer},Spring 按 mode 二选一。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mail.mode", havingValue = "smtp")
public class SmtpUserDeletionMailer implements UserDeletionMailer {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String from;

    public SmtpUserDeletionMailer(JavaMailSender mailSender,
                                  TemplateEngine templateEngine,
                                  @Value("${mail.from:Nexus Forge <no-reply@nexus-forge.local>}") String from) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.from = from;
    }

    @Override
    public void sendDeleteConfirmation(String to, String code, int ttlMinutes) {
        send(to, "【Nexus Forge】确认注销账号", "account-delete-confirm", ctx -> {
            ctx.setVariable("code", code);
            ctx.setVariable("ttlMinutes", ttlMinutes);
        });
    }

    @Override
    public void sendDeletedNotice(String to, String restoreUrl) {
        send(to, "【Nexus Forge】账号已注销", "account-deleted-notice", ctx -> {
            ctx.setVariable("email", to);
            ctx.setVariable("restoreUrl", restoreUrl);
        });
    }

    private void send(String to, String subject, String template, java.util.function.Consumer<Context> customizer) {
        try {
            Context ctx = new Context();
            customizer.accept(ctx);
            String html = templateEngine.process(template, ctx);
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("[smtp-mail] sent to={} subject=\"{}\"", mask(to), subject);
        } catch (Exception e) {
            log.warn("[smtp-mail] failed to send to={}: {}", mask(to), e.getMessage());
        }
    }

    private static String mask(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }
}
