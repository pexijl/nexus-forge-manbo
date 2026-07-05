package com.nexusforge.user.entity;

import com.nexusforge.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    @DisplayName("新建用户时，默认角色应为 ROLE_USER")
    void new_user_has_default_user_role() {
        User user = new User();
        assertThat(user.getRoles())
                .isNotNull()
                .containsExactly(Role.USER);
    }
}