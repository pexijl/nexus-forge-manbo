package com.nexusforge.testsupport;

import com.nexusforge.password.LoggingEmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 邮件捕获 helper —— dev / test 模式下,所有邮件落 {@link LoggingEmailSender}
 * 的 outDir(默认 {@code <working-dir>/build/dev-mail/});本类读这些 .eml
 * 文件,帮集成测试拿主题 / 正文 / 验证码。
 *
 * <p>由 {@code @SpringBootTest} 注入,生命周期跟随 Spring 上下文(共享 outDir)。
 * 典型用法:</p>
 * <pre>{@code
 *     mailCapture.clear();
 *     // 触发申请重置
 *     rest().postForEntity("/api/auth/password/reset/request", req, JsonNode.class);
 *     MailCapture.Mail mail = mailCapture.latestTo("alice@example.com");
 *     assertThat(mail.code()).matches("\\d{6}");
 * }</pre>
 *
 * <p><b>Code 提取</b>:依赖 {@code reset-code.html} 模板的 {@code <div class="code">NNNNNN</div>}
 * 格式,用 regex 抓取。模板改格式时这里同步改 regex。</p>
 */
@Slf4j
@Component
public class MailCapture {

    private static final Pattern CODE_PATTERN = Pattern.compile(
            "<div\\s+class=\"code\"[^>]*>(\\d{6})</div>");
    private static final Pattern TO_PATTERN = Pattern.compile("^To:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern SUBJECT_PATTERN = Pattern.compile("^Subject:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    @Autowired
    private LoggingEmailSender sender;

    /**
     * 拿收件人为指定邮箱的最新一封**有验证码**的邮件(按文件 mtime 倒序)
     *  - 用于"确认"类邮件(密码重置 / 账号注销确认)
     *
     * @throws AssertionError 若无该收件人的含验证码邮件
     */
    public Mail latestTo(String email) {
        List<Mail> mails = listAll().stream()
                .filter(m -> m.to().equalsIgnoreCase(email))
                .filter(Mail::hasCode)
                .toList();
        if (mails.isEmpty()) {
            throw new AssertionError("No mail with code for " + email
                    + " in " + sender.getOutDir() + "; existing files: "
                    + listAll().stream().map(Mail::to).toList());
        }
        return mails.get(0);
    }

    /**
     * 拿收件人为指定邮箱的最新一封**无验证码**的邮件(已注销通知 / 类似)
     *
     * @throws AssertionError 若无该收件人的无验证码邮件
     */
    public Mail latestWithoutCodeTo(String email) {
        List<Mail> mails = listAll().stream()
                .filter(m -> m.to().equalsIgnoreCase(email))
                .filter(m -> !m.hasCode())
                .toList();
        if (mails.isEmpty()) {
            throw new AssertionError("No mail without code for " + email
                    + " in " + sender.getOutDir() + "; existing files: "
                    + listAll().stream().map(Mail::to).toList());
        }
        return mails.get(0);
    }

    /** 列出 outDir 下所有邮件,按文件 mtime 倒序 */
    public List<Mail> listAll() {
        Path dir = sender.getOutDir();
        log.info("[mail-capture] listing dir: {}", dir.toAbsolutePath());
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<Mail> mails = files
                    .filter(p -> p.getFileName().toString().endsWith(".eml"))
                    .sorted(Comparator.comparing(this::safeMtime).reversed())
                    .map(this::parseFile)
                    .toList();
            log.info("[mail-capture] found {} mails in {}", mails.size(), dir);
            return mails;
        } catch (Exception e) {
            log.warn("[mail-capture] list failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** 清空 outDir 下所有 .eml —— 测试间隔离 */
    public void clear() {
        Path dir = sender.getOutDir();
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".eml"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
                    });
        } catch (IOException e) {
            log.warn("[mail-capture] clear failed: {}", e.getMessage());
        }
    }

    /** 收件人为指定 email 的邮件数量(0 表示未发送) */
    public long countFor(String email) {
        return listAll().stream()
                .filter(m -> m.to().equalsIgnoreCase(email))
                .count();
    }

    // ---------- internals ----------

    private Mail parseFile(Path file) {
        try {
            String content = Files.readString(file);
            String to = matchOrThrow(TO_PATTERN, content, "To").trim();
            String subject = matchOrThrow(SUBJECT_PATTERN, content, "Subject").trim();
            String code = matchOrThrow(CODE_PATTERN, content, "code");
            return new Mail(file, to, subject, content, code);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }

    private static String matchOrThrow(Pattern p, String content, String label) {
        Matcher m = p.matcher(content);
        if (!m.find()) {
            throw new IllegalStateException("[" + label + "] not found in email content");
        }
        return m.group(1);
    }

    private long safeMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public record Mail(Path file, String to, String subject, String body, String code) {
        /** 是否有验证码(只有"确认"类邮件有,"已注销通知"类无) */
        public boolean hasCode() { return code != null && !code.isBlank(); }
    }
}
