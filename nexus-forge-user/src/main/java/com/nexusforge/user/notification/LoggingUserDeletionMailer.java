package com.nexusforge.user.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 账号注销邮件的 logging 实现 —— 邮件落 {@code build/dev-mail/}。
 *
 * <p>启用条件:仅当 {@code mail.mode=logging}(默认值)时注册;prod 配
 * {@code mail.mode=smtp} 时本类不注册,由 {@link SmtpUserDeletionMailer} 接管。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mail.mode", havingValue = "logging", matchIfMissing = true)
public class LoggingUserDeletionMailer implements UserDeletionMailer {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final TemplateEngine templateEngine;

    public LoggingUserDeletionMailer(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    @Override
    public void sendDeleteConfirmation(String to, String code, int ttlMinutes) {
        Context ctx = new Context();
        ctx.setVariable("code", code);
        ctx.setVariable("ttlMinutes", ttlMinutes);
        String body = templateEngine.process("account-delete-confirm", ctx);
        writeFile(to, "【Nexus Forge】确认注销账号", body);
        log.info("[dev-mail] sent account-delete-confirm to={}", mask(to));
    }

    @Override
    public void sendDeletedNotice(String to, String restoreUrl) {
        Context ctx = new Context();
        ctx.setVariable("email", to);
        ctx.setVariable("restoreUrl", restoreUrl);
        String body = templateEngine.process("account-deleted-notice", ctx);
        writeFile(to, "【Nexus Forge】账号已注销", body);
        log.info("[dev-mail] sent account-deleted-notice to={}", mask(to));
    }

    private void writeFile(String to, String subject, String body) {
        try {
            Path dir = Paths.get("build", "dev-mail");
            Files.createDirectories(dir);
            String filename = "delete-" + TS.format(LocalDateTime.now()) + "-"
                    + HexFormat.of().formatHex(sha256(to.toLowerCase())).substring(0, 8) + ".eml";
            Path file = dir.resolve(filename);
            String content = "To: " + to + "\n"
                    + "Subject: " + subject + "\n"
                    + "Date: " + LocalDateTime.now() + "\n\n"
                    + body;
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[dev-mail] failed to write: {}", e.getMessage());
        }
    }

    private static String mask(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return "**" + domain;
        return local.substring(0, 2) + "***" + domain;
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
