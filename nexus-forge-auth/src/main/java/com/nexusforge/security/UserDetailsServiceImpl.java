package com.nexusforge.security;

import com.nexusforge.enums.Role;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用户详情服务实现类，负责从数据库加载用户信息并封装成 LoginUser 对象供 Spring Security 使用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public LoginUser loadUserByUsername(String account) throws UsernameNotFoundException {
        log.info("正在加载用户信息，账号: {}", account);
        User user = userRepository.findByAccount(account)
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在: " + account));
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
