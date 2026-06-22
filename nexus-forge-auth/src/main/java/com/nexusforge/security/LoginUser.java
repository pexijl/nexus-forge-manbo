package com.nexusforge.security;

import com.nexusforge.enums.Role;
import com.nexusforge.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 登录用户信息类，实现 Spring Security 的 UserDetails 接口
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginUser implements UserDetails {

    private Long userId;
    private String username;
    private String password;
    private UserStatus status;
    private List<Role> roles;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority(role.name()))  // ← role.name() 转 String
                .collect(Collectors.toList());
    }

    /**
     * 账户是否未过期，status为-2表示已注销，视为过期
     */
    @Override
    public boolean isAccountNonExpired() {
        return status != UserStatus.DELETED;  // 已注销 = 过期
    }

    /**
     * 账户是否未锁定，status为-1表示已禁用，视为锁定
     */
    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.BANNED;   // 禁用 = 锁定
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;  // 凭证永不过期
    }

    /**
     * 账户是否启用，status为0表示未激活，视为未启用
     */
    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE; // 账户启用，status为1表示正常
    }
}
