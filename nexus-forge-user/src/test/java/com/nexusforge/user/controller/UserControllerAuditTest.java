package com.nexusforge.user.controller;

import com.nexusforge.audit.Audited;
import com.nexusforge.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Audit Commit 3 structural 测试 —— 验证关键 controller 方法都加了
 * {@code @Audited} 注解,SpEL 表达式正确。运行时 AOP 行为在 commit 5
 * {@code OperationAuditIT} 端到端验证。
 *
 * <p>这里只查注解元数据,不启 Spring 上下文(快 + 稳定)。</p>
 */
@DisplayName("UserController @Audited 注解 structural 检查")
class UserControllerAuditTest {

    @Test
    @DisplayName("updateMe 加了 @Audited('user.update', resource='user', resourceId='#principal.userId()')")
    void updateMe_has_audited() throws NoSuchMethodException {
        Method m = UserController.class.getMethod("updateMe", UserPrincipal.class,
                com.nexusforge.user.dto.UpdateUserDto.class);
        Audited a = m.getAnnotation(Audited.class);
        assertThat(a).isNotNull();
        assertThat(a.value()).isEqualTo("user.update");
        assertThat(a.resource()).isEqualTo("user");
        assertThat(a.resourceId()).isEqualTo("#principal.userId()");
    }

    @Test
    @DisplayName("changePassword 加了 @Audited('user.password.change', ...)")
    void changePassword_has_audited() throws NoSuchMethodException {
        Method m = UserController.class.getMethod("changePassword", UserPrincipal.class,
                com.nexusforge.user.dto.ChangePasswordDto.class);
        Audited a = m.getAnnotation(Audited.class);
        assertThat(a).isNotNull();
        assertThat(a.value()).isEqualTo("user.password.change");
        assertThat(a.resourceId()).isEqualTo("#principal.userId()");
    }

    @Test
    @DisplayName("removeAvatar 加了 @Audited('user.avatar.remove', ...)")
    void removeAvatar_has_audited() throws NoSuchMethodException {
        Method m = UserController.class.getMethod("removeAvatar", UserPrincipal.class);
        Audited a = m.getAnnotation(Audited.class);
        assertThat(a).isNotNull();
        assertThat(a.value()).isEqualTo("user.avatar.remove");
    }

    @Test
    @DisplayName("uploadAvatar 没加 @Audited(本 commit 暂不覆盖头像上传端点;留 commit 5 IT)")
    void uploadAvatar_no_audited() throws NoSuchMethodException {
        Method m = UserController.class.getMethod("uploadAvatar",
                UserPrincipal.class, org.springframework.web.multipart.MultipartFile.class);
        // uploadAvatar 暂未加 @Audited —— 头像变更通过 updateMe 路径的
        // user.avatar.remove 也能覆盖部分场景;头像上传本身由 UserService
        // updateAvatar 内部,后续若需加再加
        assertThat(m.getAnnotation(Audited.class)).isNull();
    }
}
