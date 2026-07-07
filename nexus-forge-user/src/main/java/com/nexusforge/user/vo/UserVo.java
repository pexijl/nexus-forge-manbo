package com.nexusforge.user.vo;

import com.nexusforge.enums.UserStatus;
import com.nexusforge.user.entity.User;
import com.nexusforge.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Schema(description = "用户视图对象 —— 返回给前端的用户信息")
public class UserVo {
    @Schema(description = "用户 ID", example = "1")
    private Long id;

    @Schema(description = "用户名（唯一）", example = "alice")
    private String username;

    @Schema(description = "邮箱", example = "alice@example.com")
    private String email;

    @Schema(description = "昵称", example = "Alice")
    private String nickname;

    @Schema(description = "头像 URL（预签名或 CDN 加速）")
    private String avatarUrl;

    @Schema(description = "手机号", example = "13800000000")
    private String phone;

    @Schema(description = "账号状态")
    private UserStatus status;

    @Schema(description = "角色权限字符串列表", example = "[\"ROLE_USER\"]")
    private List<String> roles;

    @Schema(description = "最后登录时间")
    private OffsetDateTime lastLoginAt;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;

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
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());
        return vo;
    }
}
