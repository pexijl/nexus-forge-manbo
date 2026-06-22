package com.nexusforge.user.vo;

import com.nexusforge.enums.UserStatus;
import com.nexusforge.user.entity.User;
import com.nexusforge.enums.Role;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class UserVo {
    private Long id;
    private String username;
    private String email;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private UserStatus status;
    private List<String> roles;

    public static UserVo of(User user) {
        UserVo vo = new UserVo();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        vo.setRoles(user.getRoles().stream()
                .map(Role::getAuthority)  // "ROLE_USER", "ROLE_ADMIN"
                .toList());
        return vo;
    }
}
