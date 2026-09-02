package com.nexusforge.password;

import com.nexusforge.mail.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * dev / test 邮件发送器 —— 把邮件落 {@code build/dev-mail/} + log INFO(主题 / 收件人打码)。
 *
 * <p>设计动机:dev / 集成测试不想引外部 SMTP 容器(GreenMail / MailHog),
 * 直接落盘 + log 即可让开发者 / 测试代码拿到邮件内容;{@code build/}
 * 已在 {@code .gitignore},不会污染版本库。</p>
 *
 * <p>工作目录:运行 {@code bootRun} 时是仓库根,落 {@code <repo>/build/dev-mail/};
 * 跑 {@code @SpringBootTest} 时是当前子模块,落 {@code nexus-forge-auth/build/dev-mail/}。
 * 两者都在 .gitignore 覆盖范围内。集成测试通过 {@link #getOutDir()} 拿到实际路径。</p>
 *
 * <p>启用条件:仅当 {@code mail.mode=logging}(默认值)时注册;prod 配置
 * {@code mail.mode=smtp} 时本类不会注册,由 {@link SmtpEmailSender} 接管。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mail.mode", havingValue = "logging", matchIfMissing = true)   // matchIfMissing=true:默认 logging,开箱即用;prod 显式配 mail.mode=smtp 时本类不注册,由 SmtpEmailSender 接管
public class LoggingEmailSender implements EmailSender {

    /** 文件名时间戳格式;精度到毫秒,避免同秒多封邮件冲突(同 ms 再靠 hash 8 区分) */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path outDir;

    public LoggingEmailSender() {
        this(Paths.get("build", "dev-mail"));
    }

    /** 测试 / 自定义路径用 */
    public LoggingEmailSender(Path outDir) {
        this.outDir = outDir;
    }

    /**
     * 发送邮件 —— 落盘到 {@link #outDir} + log INFO(收件人打码)。
     *
     * <p><b>文件命名</b>:{@code <timestamp>-<emailHash8>.eml}
     * <ul>
     *   <li>timestamp:毫秒精度({@link #TS_FMT}),避免同秒多封邮件冲突</li>
     *   <li>emailHash8:sha256(to.toLowerCase()) 前 8 hex —— 同账号多封邮件也能区分</li>
     * </ul>
     *
     * <p><b>邮件格式</b>:简化 RFC 822(只 To / Subject / Date),<b>不是</b>合规的 EML——
     * dev / test 场景够用;需要 MIME 头 / multipart 时改用 SmtpEmailSender 走真 SMTP。
     *
     * <p><b>原子写</b>:用 {@link StandardOpenOption#CREATE_NEW} 防止覆盖已有邮件
     * (同 ms 同 hash 仍冲突,会被拒绝进 catch——这种情况在 dev/test 实际不会发生)。
     *
     * <p><b>尽力送达</b>:落盘失败仅 log 不抛——业务侧(密码重置)假设已发送;
     * dev/test 场景邮件失败不应阻塞业务流程。
     */
    @Override
    public void send(String to, String subject, String body) {
        // 1) log INFO:用 maskEmail 打码防 log 泄露;body 只记长度,正文从 .eml 文件读
        log.info("[dev-mail] to={} subject=\"{}\" bodyLength={}",
                maskEmail(to), subject, body == null ? 0 : body.length());

        try {
            // 2) createDirectories 幂等:目录已存在不抛
            Files.createDirectories(outDir);

            // 3) 文件名:timestamp(毫秒) + sha256 邮箱前 8 hex,确保同时多封邮件不冲突
            String filename = TS_FMT.format(LocalDateTime.now())
                    + "-"
                    + HexFormat.of().formatHex(sha256(to.toLowerCase())).substring(0, 8)
                    + ".eml";
            Path file = outDir.resolve(filename);

            // 4) 简化 RFC 822:To / Subject / Date + 空行 + body(body=null 写空字符串)
            String content = "To: " + to + "\n"
                    + "Subject: " + subject + "\n"
                    + "Date: " + LocalDateTime.now() + "\n"
                    + "\n"
                    + (body == null ? "" : body);

            // 5) CREATE_NEW 原子写:不覆盖已有文件;同 ms+hash 冲突会抛 IOException 进 catch
            Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            // 邮件"尽力送达"原则 —— 落盘失败仅 log,业务侧假设已发送
            log.warn("[dev-mail] failed to write email to disk: {}", e.getMessage());
        }
    }

    /** 测试 helper / 集成测试用 —— 获取实际输出目录 */
    public Path getOutDir() {
        return outDir;
    }

    /**
     * 邮箱本地部分打码(防 log 泄露),例 {@code alice@example.com} → {@code al***@example.com}。
     *
     * <p>边界处理:
     * <ul>
     *   <li>{@code null} 或不含 {@code @} → {@code "***"}</li>
     *   <li>本地部分 ≤ 2 字符(如 {@code ab@x.com}) → {@code "**@x.com"}</li>
     *   <li>本地部分 > 2 字符 → 保留前 2 字符 + {@code ***} + 域名</li>
     * </ul>
     *
     * <p>包级可见(非 private):让 {@code LoggingEmailSenderTest} 单测可直接测,无需反射。
     */
    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return "**" + domain;
        return local.substring(0, 2) + "***" + domain;
    }

    /**
     * UTF-8 字节的 SHA-256 摘要;用于文件名 hash(同邮箱同时间戳区分多封邮件)。
     * JDK 必带 SHA-256,此处 {@link NoSuchAlgorithmException} 实际不可触发,留作防御性兜底。
     */
    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
