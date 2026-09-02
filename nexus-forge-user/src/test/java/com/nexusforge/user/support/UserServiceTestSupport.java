package com.nexusforge.user.support;

import com.nexusforge.audit.AuditEvent;
import com.nexusforge.audit.AuditLogger;
import com.nexusforge.enums.Role;
import com.nexusforge.enums.UserStatus;
import com.nexusforge.file.FileClient;
import com.nexusforge.user.AccountLifecycleProperties;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.enums.AccountLifecycleAction;
import com.nexusforge.user.repository.UserRepository;
import com.nexusforge.user.service.AccountAnonymizer;
import com.nexusforge.user.service.AccountLifecycleService;
import com.nexusforge.user.service.UserRoleProvider;
import com.nexusforge.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Set;

/**
 * UserService 单元测试基类
 *
 * 设计要点:
 * - @Mock 注入 UserRepository / UserRoleProvider / AccountAnonymizer / ApplicationEventPublisher
 *   / FileClient / AuditLogger / AccountLifecycleProperties
 * - PasswordEncoder 用真实 BCryptPasswordEncoder(验证哈希链没断)
 * - AccountLifecycleService 用真实构造(只 mock 它依赖的字段);UserService.banUser
 *   会委派给它,ban 相关行为由 AccountLifecycleServiceTest 单独覆盖
 * - 不使用 @InjectMocks(构造器里混了真实 PasswordEncoder)
 *   改在 @BeforeEach 里手动 new UserService(repo, role, encoder, fileClient, accountLifecycleService)
 */
@ExtendWith(MockitoExtension.class)
public abstract class UserServiceTestSupport {

    @Mock
    protected UserRepository userRepository;

    @Mock
    protected UserRoleProvider userRoleProvider;

    @Mock
    protected AccountAnonymizer accountAnonymizer;

    @Mock
    protected ApplicationEventPublisher eventPublisher;

    @Mock
    protected FileClient fileClient;

    @SuppressWarnings("unchecked")
    @Mock
    protected AuditLogger<AccountLifecycleAction> auditLogger;

    /**
     * 真实 BCryptPasswordEncoder,让 passwordEncoder.matches / encode 走真逻辑
     */
    protected final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * AccountLifecycleService 用真实构造 + 全部 mock 依赖
     * (其 ban/unban 行为在 AccountLifecycleServiceTest 覆盖,这里只确保 UserService.banUser
     * 委派链路通)
     */
    protected AccountLifecycleService accountLifecycleService;

    /**
     * 手动构造,绕开 @InjectMocks 只能填 mock 的限制
     */
    protected UserService userService;

    @BeforeEach
    void initUserService() {
        AccountLifecycleProperties props = new AccountLifecycleProperties();
        // StringRedisTemplate / UserDeletionMailer 在 UserService 自身测试中用不到,
        // 用 mock 即可(满足构造器参数,实际不被调用)
        org.springframework.data.redis.core.StringRedisTemplate mockRedis =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        com.nexusforge.user.notification.UserDeletionMailer mockMailer =
                org.mockito.Mockito.mock(com.nexusforge.user.notification.UserDeletionMailer.class);
        com.nexusforge.lock.DistributedLockTemplate mockLock =
                org.mockito.Mockito.mock(com.nexusforge.lock.DistributedLockTemplate.class);
        accountLifecycleService = new AccountLifecycleService(userRepository, userRoleProvider,
                accountAnonymizer, eventPublisher, auditLogger, props,
                passwordEncoder, mockRedis, mockMailer, mockLock);
        userService = new UserService(userRepository, userRoleProvider,
                passwordEncoder, fileClient, accountLifecycleService);
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