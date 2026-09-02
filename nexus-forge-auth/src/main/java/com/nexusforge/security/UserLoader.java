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
 * 按 userId 加载 {@link LoginUser} —— {@code com.nexusforge.service.AuthService}
 * 在 refresh / issueTokens 时拿最新用户信息的薄包装。
 *
 * <p><b>与 UserDetailsServiceImpl 的区别</b>:
 * <ul>
 *   <li>{@code UserDetailsServiceImpl} —— 按 <b>account</b>(username / email)加载,
 *       供 DaoAuthenticationProvider 密码登录用</li>
 *   <li>本类 —— 按 <b>userId</b> 加载,供 AuthService 在已知 userId 的场景用
 *       (从 JWT sub claim 解析后)</li>
 * </ul>
 *
 * <p><b>设计目的</b>:让 AuthService 不直接依赖 UserService,避免 auth → user 反向依赖
 * (auth 模块本就需要 user 实体查询,但通过仓库接口而非 Service 层耦合)。
 *
 * <p><b>唯一调用方</b>:{@code AuthService.refresh} 在重新签发时调用一次——
 * "重新读取用户信息"是设计意图:让角色变更后能立即生效,避免用签发时缓存的 LoginUser。
 *
 * <p><b>⚠️ 异常名实不符</b>:找不到时抛 {@link UsernameNotFoundException},
 * 但本类是按 <b>userId</b> 找用户(不是 username),Spring Security 没为 userId 找不到
 * 定义专门的异常,这是临时方案。{@code AuthController.refresh} 只 catch
 * {@code AuthException},该异常会冒泡到 {@code GlobalExceptionHandler} 兜底——
 * <b>改进点</b>:应改抛 {@code AuthException(ResultCode.USER_NOT_FOUND)} 或
 * 自定义 {@code UserNotFoundByIdException},让前端拿到明确的 4xx 响应。
 *
 * @see com.nexusforge.service.AuthService#refresh 调用方
 * @see com.nexusforge.security.UserDetailsServiceImpl 按 account 加载的兄弟
 */
@Component
@RequiredArgsConstructor
public class UserLoader {

    /** JPA 仓库;{@code findById(userId)} 是 Spring Data 内置方法 */
    private final UserRepository userRepository;

    /**
     * 按 userId 加载 LoginUser(供 {@code AuthService.refresh} / {@code issueTokens} 重新读取用)。
     *
     * <p><b>⚠️ 异常名实不符</b>:找不到抛 {@link UsernameNotFoundException} 但这是按
     * <b>userId</b> 找,不是按 username;见类 Javadoc 改进点(应改 {@code AuthException(USER_NOT_FOUND)})。
     *
     * @param userId 用户主键
     * @return 装好的 LoginUser
     * @throws UsernameNotFoundException 用户不存在(命名待改进;实际异常类型与按 username 找不到共用)
     */
    public LoginUser loadById(Long userId) {
        // 1) 查 DB:findById 是 Spring Data 内置方法;Optional.orElseThrow 抛 UsernameNotFoundException
        //    ⚠️ 命名待改进:应该是 AuthException(USER_NOT_FOUND) 或自定义 UserNotFoundByIdException
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + userId));

        // 2) 防御性拷贝:JPA 实体 User.getRoles() 返 Hibernate 托管集合(PersistentBag),
        //    包装成普通可变 List 解耦,避免后续 stream / 序列化被 Hibernate 干扰
        List<Role> roles = new ArrayList<>(user.getRoles());

        // 3) 构造 LoginUser:同 UserDetailsServiceImpl.loadUserByUsername,password 含 BCrypt 哈希
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .password(user.getPassword())
                .status(user.getStatus())
                .roles(roles)
                .build();
    }
}
