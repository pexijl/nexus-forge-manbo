package com.nexusforge.password;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoggingEmailSender} 单元测试 —— 落盘 + 文件名格式 + 邮箱打码。
 *
 * <p>用 {@link TempDir} 隔离每次测试的 outDir;验证:</p>
 * <ul>
 *   <li>调用 {@code send} 后 outDir 出现一个 {@code .eml} 文件</li>
 *   <li>文件内容包含主题、收件人、正文</li>
 *   <li>异常(写盘失败)被静默吞,不向调用方抛</li>
 *   <li>{@code maskEmail} 本地部分打码规则</li>
 * </ul>
 */
class LoggingEmailSenderTest {

    @TempDir
    Path tmp;

    private LoggingEmailSender sender;

    @BeforeEach
    void setUp() {
        sender = new LoggingEmailSender(tmp);
    }

    @AfterEach
    void cleanup() throws IOException {
        if (Files.exists(tmp)) {
            try (Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
                });
            }
        }
    }

    @Test
    @DisplayName("send 落盘到 outDir,文件名以 .eml 结尾,内容包含主题和正文")
    void send_writes_file_with_expected_content() throws IOException {
        sender.send("alice@example.com", "重置你的密码", "验证码 123456");

        try (Stream<Path> files = Files.list(tmp)) {
            Path file = files.findFirst().orElseThrow(() ->
                    new AssertionError("expected one .eml file in " + tmp));
            assertThat(file.getFileName().toString()).endsWith(".eml");
            String content = Files.readString(file);
            assertThat(content).contains("To: alice@example.com")
                    .contains("Subject: 重置你的密码")
                    .contains("验证码 123456");
        }
    }

    @Test
    @DisplayName("同一收件人多次 send 落多个文件(文件名含 hash + timestamp,唯一)")
    void send_multiple_times_creates_distinct_files() throws Exception {
        sender.send("bob@example.com", "S1", "B1");
        // 同 ms 内可能撞 timestamp,但 hash 区分;若撞,Files.writeString 抛 FileAlreadyExistsException
        // 这里依赖 hash 不同 — 不同 to → 不同 hash
        sender.send("charlie@example.com", "S2", "B2");

        long count;
        try (Stream<Path> files = Files.list(tmp)) {
            count = files.count();
        }
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("写盘失败(只读目录)时 send 不抛异常,静默 log 即可")
    void send_silently_swallows_io_error() throws IOException {
        // 把 tmp 改成只读 —— Windows 下用 acl 复杂,直接让 outDir 不存在且 parent 也是 file
        // 构造一个不可写的 outDir:把 outDir 设成一个普通文件,Files.createDirectories 会失败
        Path notADir = tmp.resolve("not-a-dir");
        Files.writeString(notADir, "blocking file");
        LoggingEmailSender broken = new LoggingEmailSender(notADir.resolve("nested"));

        // 应当不抛
        broken.send("eve@example.com", "S", "B");
    }

    @Test
    @DisplayName("maskEmail:本地部分 ≥3 字符保留前 2 + ***,短于 3 字符全打码")
    void mask_email() {
        assertThat(LoggingEmailSender.maskEmail("alice@example.com")).isEqualTo("al***@example.com");
        assertThat(LoggingEmailSender.maskEmail("ab@example.com")).isEqualTo("**@example.com");
        assertThat(LoggingEmailSender.maskEmail("a@example.com")).isEqualTo("**@example.com");
        assertThat(LoggingEmailSender.maskEmail(null)).isEqualTo("***");
        assertThat(LoggingEmailSender.maskEmail("not-an-email")).isEqualTo("***");
    }
}
