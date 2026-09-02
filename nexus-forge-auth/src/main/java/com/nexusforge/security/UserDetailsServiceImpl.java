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
import java.util.List;

/**
 * Spring Security {@link UserDetailsService} 的实现 —— DaoAuthenticationProvider
 * 密码登录的核心依赖:用账号查 DB,包成 {@link LoginUser} 返给 Spring Security。
 *
 * <p><b>调用链</b>:{@code AuthController.login} → AuthenticationManager.authenticate
 * → DaoAuthenticationProvider → 本类.loadUserByUsername → 返 LoginUser →
 * Spring Security 用 LoginUser 的 password 字段比对 + 4 个 isXxx 方法判断状态。
 *
 * <p><b>account 字段语义</b>:username 或 email 都允许(由 {@code UserRepository.findByAccount}
 * 实现判断),调用方 {@code AuthController.login} 的 LoginRequest.account 字段统一接受两种格式。
 *
 * <p><b>异常处理</b>:账号不存在抛 {@link UsernameNotFoundException},
 * 被 DaoAuthenticationProvider catch 后包成 {@code BadCredentialsException},
 * 最终由 {@code AuthController.login} 统一返 {@code INVALID_CREDENTIALS}(防账号枚举)。
 *
 * @see com.nexusforge.controller.AuthController.login 调用方
 * @see com.nexusforge.user.repository.UserRepository#findByAccount 实际查询
 * @see com.nexusforge.security.LoginUser 返回类型
 * @see com.nexusforge.security.UserPrincipal JWT 路径的对应(不可变 record)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    /** JPA 仓库;{@code findByAccount} 是 Spring Data 约定方法名(自动生成 JPQL),
     *  内部支持 username / email 双匹配 */
    private final UserRepository userRepository;

    /**
     * 加载用户详情 —— 供 DaoAuthenticationProvider 密码登录用。
     *
     * <p><b>⚠️ INFO log 含明文 account</b>:Spring Security 默认行为,
     * log 里会出现真实账号;若生产对 log 审计严格,建议改 DEBUG 级别 + 改用账号 hash
     * (类似 {@code LoggingEmailSender.maskEmail})。
     *
     * @param account username 或 email(由 {@code UserRepository.findByAccount} 适配)
     * @return 装好的 LoginUser(密码字段含 BCrypt 哈希,供 DaoAuthenticationProvider 比对)
     * @throws UsernameNotFoundException 账号不存在;
     *                                  被 AuthenticationManager 兜底为 BadCredentialsException
     */
    @Override
    public LoginUser loadUserByUsername(@NonNull String account) throws UsernameNotFoundException {
        // 1) INFO log:含明文 account(⚠️ 见方法 Javadoc)
        log.info("正在加载用户信息，账号: {}", account);

        // 2) 查 DB:findByAccount 是 Spring Data 约定方法名(自动生成 JPQL);
        //    Optional.orElseThrow 抛 UsernameNotFoundException → 后续被包成 BadCredentialsException
        User user = userRepository.findByAccount(account)
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在: " + account));

        // 3) 防御性拷贝:JPA 实体 User.getRoles() 返的是 Hibernate 托管集合(PersistentBag),
        //    直接传给 LoginUser 后,后续 stream() / 序列化可能被 Hibernate 干扰或抛
        //    LazyInitializationException;new ArrayList<>() 包装成普通可变 List 解耦
        List<Role> roles = new ArrayList<>(user.getRoles());

        // 4) 构造 LoginUser:password 含 BCrypt 哈希(供 DaoAuthenticationProvider 比对),
        //    不入 JWT(安全,见 LoginUser.password 字段 Javadoc)
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .password(user.getPassword())
                .status(user.getStatus())
                .roles(roles)
                .build();
    }

}
