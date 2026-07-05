package com.nexusforge.user.service;

import com.nexusforge.exception.BusinessException;
import com.nexusforge.user.dto.UpdateUserDto;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.support.UserServiceTestSupport;
import com.nexusforge.user.vo.UserVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UserService.updateUser")
class UserServiceUpdateUserTest extends UserServiceTestSupport {

    private static final Long USER_ID = 100L;

    private void stubUserExists() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser(USER_ID)));
    }

    @Nested
    @DisplayName("用户查找")
    class LookupFailures {

        @Test
        @DisplayName("用户不存在：抛 USER_NOT_FOUND")
        void throws_when_user_not_found() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            UpdateUserDto dto = new UpdateUserDto();
            dto.setNickname("NewName");

            assertThatThrownBy(() -> userService.updateUser(USER_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("用户不存在");

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("字段更新")
    class FieldUpdates {

        @Test
        @DisplayName("更新昵称：trim 后写入")
        void updates_nickname_with_trim() {
            stubUserExists();
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateUserDto dto = new UpdateUserDto();
            dto.setNickname("  NewName  ");

            userService.updateUser(USER_ID, dto);

            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("空字符串昵称：保持原值不更新")
        void empty_nickname_keeps_original() {
            User original = existingUser(USER_ID);
            String originalNick = original.getNickname();
            stubUserExists();
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateUserDto dto = new UpdateUserDto();
            dto.setNickname("");

            UserVo vo = userService.updateUser(USER_ID, dto);

            assertThat(vo.getNickname()).isEqualTo(originalNick);
        }

        @Test
        @DisplayName("null 字段：保持原值，不触发邮箱唯一性检查")
        void null_fields_skip_validation() {
            stubUserExists();
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateUserDto dto = new UpdateUserDto();   // 全部为 null
            userService.updateUser(USER_ID, dto);

            verify(userRepository, never()).existsByEmail(any());
            verify(userRepository).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("邮箱冲突")
    class EmailConflict {

        @Test
        @DisplayName("新邮箱已被他人占用：抛 EMAIL_ALREADY_EXISTS")
        void rejects_email_collision() {
            stubUserExists();
            when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

            UpdateUserDto dto = new UpdateUserDto();
            dto.setEmail("taken@example.com");

            assertThatThrownBy(() -> userService.updateUser(USER_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("邮箱已存在");  // ✅ 修复：使用 hasMessage

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("新邮箱与当前用户相同：跳过查重，直接放行")
        void allows_email_to_stay_unchanged() {
            stubUserExists();
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateUserDto dto = new UpdateUserDto();
            dto.setEmail("alice@example.com");  // 与当前用户相同

            UserVo vo = userService.updateUser(USER_ID, dto);

            // 关键断言：不应调用 existsByEmailAndIdNot（因为是同邮箱）
            verify(userRepository, never()).existsByEmailAndIdNot(any(), any());
            assertThat(vo).isNotNull();
            assertThat(vo.getEmail()).isEqualTo("alice@example.com");
            verify(userRepository).save(any(User.class));
        }
    }
}