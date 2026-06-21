package com.nexusforge.user.vo;

import com.nexusforge.user.entity.User;
import lombok.Data;

@Data
public class UserVo {
    private Long id;
    private String username;
    private String email;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private Integer status;
    private String role;

    public static UserVo of(User user) {
        UserVo vo = new UserVo();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        vo.setRole(user.getRole());
        return vo;
    }
}
