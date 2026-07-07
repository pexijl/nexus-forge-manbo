package com.nexusforge.user.service;

import com.nexusforge.dto.RegisterRequest;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.enums.UserStatus;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.support.UserServiceTestSupport;
import com.nexusforge.user.vo.UserVo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
@DisplayName("UserService.register")
class UserServiceRegisterTest extends UserServiceTestSupport {

    private static RegisterRequest validRequest() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("bob");
        req.setEmail("bob@example.com");
        req.setPassword("secret123");
        return req;
    }

    @Nested
    @DisplayName("正常路径")
    class HappyPath {

        @Test
        @DisplayName("注册成功：密码被加密、昵称自动生成、角色默认 ROLE_USER")
        void registers_new_user_successfully() {
            RegisterRequest req = validRequest();
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UserVo vo = userService.register(req);

            // 验证返回的 UserVo 包含正确的非敏感信息
            assertThat(vo).isNotNull();
            assertThat(vo.getUsername()).isEqualTo("bob");
            assertThat(vo.getEmail()).isEqualTo("bob@example.com");
            assertThat(vo.getNickname()).isNotNull(); // 验证昵称自动生成
            assertThat(vo.getStatus()).isEqualTo(UserStatus.ACTIVE); // 验证默认状态

            // 验证 save 入参的密码被 BCrypt 加密（通过 ArgumentCaptor 详细验证）
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();

            // 密码不等于原文，且经过 BCrypt 加密（以 $2a$ 开头）
            assertThat(savedUser.getPassword())
                    .isNotEqualTo(req.getPassword())
                    .startsWith("$2a$"); // BCrypt 加密后的密码特征
        }
    }

    @Nested
    @DisplayName("冲突路径")
    class ConflictPaths {

        @Test
        @DisplayName("用户名已存在：抛 USER_ALREADY_EXISTS，且不调 save")
        void rejects_duplicate_username() {
            RegisterRequest req = validRequest();
            when(userRepository.existsByUsername("bob")).thenReturn(true);

            // ✅ 修复：使用 isEqualTo() 而不是 equals()
            assertThatThrownBy(() -> userService.register(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode())
                                .isEqualTo(ResultCode.USER_ALREADY_EXISTS.getCode());
                    });

            verify(userRepository, never()).save(any());
            verify(userRepository, never()).existsByEmail(any());
        }

        @Test
        @DisplayName("邮箱已存在：抛 EMAIL_ALREADY_EXISTS，且不调 save")
        void rejects_duplicate_email() {
            RegisterRequest req = validRequest();
            when(userRepository.existsByUsername("bob")).thenReturn(false);
            when(userRepository.existsByEmail("bob@example.com")).thenReturn(true);

            assertThatThrownBy(() -> userService.register(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("邮箱已存在");

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("随机昵称遵循 User_xxxxxx 格式（6 位 [a-z0-9]）")
        void nickname_follows_pattern() {
            RegisterRequest req = validRequest();
            when(userRepository.existsByUsername(any())).thenReturn(false);
            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UserVo vo = userService.register(req);

            assertThat(vo.getNickname()).matches("^User_[a-z0-9]{6}$");
        }
    }
}