package com.nexusforge.controller;

import com.nexusforge.audit.Audited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Audit Commit 3 structural 测试 —— 验证 {@code FileController} 关键端点
 * 都加了 {@code @Audited}。运行时 AOP 行为在 commit 5
 * {@code OperationAuditIT} 端到端验证。
 */
@DisplayName("FileController @Audited 注解 structural 检查")
class FileControllerAuditTest {

    @Test
    @DisplayName("upload 加了 @Audited('file.upload', recordArgs=true)")
    void upload_has_audited() throws NoSuchMethodException {
        Method m = FileController.class.getMethod("upload",
                org.springframework.web.multipart.MultipartFile.class,
                com.nexusforge.file.FileBizType.class);
        Audited a = m.getAnnotation(Audited.class);
        assertThat(a).isNotNull();
        assertThat(a.value()).isEqualTo("file.upload");
        assertThat(a.resource()).isEqualTo("file");
        assertThat(a.recordArgs()).isTrue();
    }

    @Test
    @DisplayName("deleteById 加了 @Audited('file.delete', resourceId='#id')")
    void deleteById_has_audited() throws NoSuchMethodException {
        Method m = FileController.class.getMethod("deleteById", Long.class);
        Audited a = m.getAnnotation(Audited.class);
        assertThat(a).isNotNull();
        assertThat(a.value()).isEqualTo("file.delete");
        assertThat(a.resourceId()).isEqualTo("#id");
    }

    @Test
    @DisplayName("adminSearch 没加 @Audited(admin 自身调,记到 user 维度会重复)")
    void adminSearch_no_audited() throws NoSuchMethodException {
        Method m = FileController.class.getMethod("adminSearch",
                Long.class, com.nexusforge.file.FileBizType.class,
                com.nexusforge.file.entity.FileStatus.class, int.class, int.class);
        // admin 查操作不进 operation_audit(写一行 user=admin 的"查"无业务意义);
        // 真要审计应该走 account_lifecycle_log(管理员 ban / unban 那条)
        assertThat(m.getAnnotation(Audited.class)).isNull();
    }
}
