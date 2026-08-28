package com.nexusforge.security;

import com.nexusforge.enums.Role;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 封装“按 userId 加载 LoginUser”，避免 AuthService 直接依赖 UserService
 */
@Component
@RequiredArgsConstructor
public class UserLoader {

    private final UserRepository userRepository;

    public LoginUser loadById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + userId));
        List<Role> roles = new ArrayList<>(user.getRoles());
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .password(user.getPassword())
                .status(user.getStatus())
                .roles(roles)
                .build();
    }
}
