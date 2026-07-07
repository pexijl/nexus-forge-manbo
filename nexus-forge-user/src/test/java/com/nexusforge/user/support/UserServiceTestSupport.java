package com.nexusforge.user.support;

import com.nexusforge.enums.Role;
import com.nexusforge.enums.UserStatus;
import com.nexusforge.file.FileClient;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import com.nexusforge.user.service.UserRoleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Set;

/**
 * UserService 单元测试基类
 *
 * 设计要点：
 * - @Mock 注入 UserRepository / UserRoleProvider / ApplicationEventPublisher / FileClient
 * - PasswordEncoder 用真实 BCryptPasswordEncoder（验证哈希链没断）
 * - 不使用 @InjectMocks（构造器里混了真实 PasswordEncoder）
 *   改在 @BeforeEach 里手动 new UserService(repo, role, events, encoder, fileClient)
 */
@ExtendWith(MockitoExtension.class)
public abstract class UserServiceTestSupport {

    @Mock
    protected UserRepository userRepository;

    @Mock
    protected UserRoleProvider userRoleProvider;

    @Mock
    protected ApplicationEventPublisher eventPublisher;

    @Mock
    protected FileClient fileClient;

    /**
     * 真实 BCryptPasswordEncoder，让 passwordEncoder.matches / encode 走真逻辑
     */
    protected final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 手动构造，绕开 @InjectMocks 只能填 mock 的限制
     */
    protected UserService userService;

    @BeforeEach
    void initUserService() {
        userService = new UserService(userRepository, userRoleProvider, eventPublisher, passwordEncoder, fileClient);
    }

    // ---------------- 工厂方法（保持原样） ----------------

    protected User existingUser(Long id) {
        User u = new User();
        u.setId(id);
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        u.setNickname("Alice");
        u.setPassword(passwordEncoder.encode("oldPass123"));
        u.setStatus(UserStatus.ACTIVE);
        u.setRoles(defaultRoles());
        return u;
    }

    protected Set<Role> defaultRoles() {
        Set<Role> roles = new HashSet<>();
        roles.add(Role.USER);
        return roles;
    }

}