package com.nexusforge.user.service;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.user.dto.ChangePasswordDto;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.support.UserServiceTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UserService.changePassword")
class UserServiceChangePasswordTest extends UserServiceTestSupport {

    private static final Long USER_ID = 200L;

    private static ChangePasswordDto dto(String oldPwd, String newPwd) {
        return new ChangePasswordDto(oldPwd, newPwd);
    }

    @Nested
    @DisplayName("用户查找")
    class LookupFailures {

        @Test
        @DisplayName("用户不存在：抛 USER_NOT_FOUND")
        void throws_when_user_not_found() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.changePassword(USER_ID, dto("old", "new")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ResultCode.USER_NOT_FOUND.getMessage());

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("旧密码校验")
    class OldPasswordValidation {

        @Test
        @DisplayName("旧密码错误：抛 OLD_PASSWORD_INCORRECT")
        void rejects_wrong_old_password() {
            User user = existingUser(USER_ID);  // 真实密码是 oldPass123
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    userService.changePassword(USER_ID, dto("wrongOld", "newPass123")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ResultCode.OLD_PASSWORD_INCORRECT.getMessage());

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("新旧密码相同：抛 NEW_PASSWORD_SAME_AS_OLD")
        void rejects_same_password() {
            User user = existingUser(USER_ID);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    userService.changePassword(USER_ID, dto("oldPass123", "oldPass123")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ResultCode.NEW_PASSWORD_SAME_AS_OLD.getMessage());

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("成功路径")
    class SuccessPath {

        @Test
        @DisplayName("正确流程：密码被重新 BCrypt 编码后保存")
        void changes_password_and_saves() {
            User user = existingUser(USER_ID);
            String oldEncoded = user.getPassword();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.changePassword(USER_ID, dto("oldPass123", "newSecret456"));

            // 捕获 save 时的 User，验证密码被改写
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            User saved = captor.getValue();

            assertThat(saved.getPassword())
                    .isNotEqualTo(oldEncoded)
                    .isNotEqualTo("newSecret456");  // 必须是哈希，不是明文
            assertThat(passwordEncoder.matches("newSecret456", saved.getPassword())).isTrue();
            assertThat(passwordEncoder.matches("oldPass123", saved.getPassword())).isFalse();
        }
    }
}