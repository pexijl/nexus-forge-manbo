package com.nexusforge.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

/**
 * 封装“按 userId 加载 LoginUser”，避免 AuthService 直接依赖 UserService
 */
@Component
@RequiredArgsConstructor
public class UserLoader {

    private final UserDetailsService userDetailsService;

    public LoginUser loadById(Long userId) {
        return (LoginUser) userDetailsService.loadUserByUsername(String.valueOf(userId));
    }
}
