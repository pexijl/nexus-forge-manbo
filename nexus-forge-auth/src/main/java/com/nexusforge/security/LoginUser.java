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
 * 登录用户信息 —— 实现 Spring Security 的 {@link UserDetails} 接口,
 * 作为 DaoAuthenticationProvider 密码登录认证后的 principal 载体。
 *
 * <p><b>何时被构造</b>:{@code AuthController.login} → AuthenticationManager.authenticate
 * → UserDetailsServiceImpl.loadUserByUsername → 构造 LoginUser → 由 Spring Security
 * 包装进 {@code Authentication.getPrincipal()}。
 *
 * <p><b>字段职责</b>:
 * <ul>
 *   <li>{@code userId} — 用户主键;供 JWT sub claim / 业务查询用</li>
 *   <li>{@code username} — 登录账号</li>
 *   <li>{@code password} — BCrypt 哈希;仅认证时比对,<b>不入 JWT</b>(防泄露)</li>
 *   <li>{@code status} — 用户状态枚举;决定 4 个 isXxx 方法返回值</li>
 *   <li>{@code roles} — 角色列表;决定 {@link #getAuthorities()}</li>
 * </ul>
 *
 * <p><b>UserStatus → UserDetails 映射</b>:
 * <table>
 *   <tr><th>UserStatus</th><th>isAccountNonExpired</th><th>isAccountNonLocked</th><th>isEnabled</th></tr>
 *   <tr><td>ACTIVE(1)</td><td>true</td><td>true</td><td><b>true</b></td></tr>
 *   <tr><td>INACTIVE(0)</td><td>true</td><td>true</td><td><b>false</b></td></tr>
 *   <tr><td>BANNED(-1)</td><td>true</td><td><b>false</b></td><td>false</td></tr>
 *   <tr><td>DELETED(-2)</td><td><b>false</b></td><td>true</td><td>false</td></tr>
 * </table>
 *
 * <p><b>与 UserPrincipal 的区别</b>:
 * <ul>
 *   <li>{@code LoginUser}(本类)——可变 POJO,实现 UserDetails;用于<b>密码登录</b>路径,
 *       含 password / status / roles</li>
 *   <li>{@code UserPrincipal}——{@code record},不可变;用于<b>JWT 鉴权</b>路径,
 *       只含 userId + username(其他从 Claims / Redis 拿)</li>
 * </ul>
 *
 * <p><b>注意</b>:{@code UserStatus.DELETED} 已 {@code @Deprecated}(AGENTS.md 软删约定:
 * 删应用 {@code deleted_at} 字段而非 status);{@link #isAccountNonExpired} 仍按
 * DELETED 判过期——已软删但 status 仍为 ACTIVE 的用户本方法会误判为未过期,
 * 见后续可改进点(应改判 {@code user.deletedAt == null})。
 *
 * @see com.nexusforge.security.UserPrincipal JWT 鉴权用
 * @see com.nexusforge.security.UserDetailsServiceImpl loadUserByUsername
 * @see com.nexusforge.enums.UserStatus 状态枚举(DELETED 已 deprecated)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginUser implements UserDetails {

    /** 用户主键;供 JWT sub claim 与业务查询 */
    private Long userId;
    /** 登录账号(username / email) */
    private String username;
    /** BCrypt 密码哈希;仅 AuthenticationManager 比对用,<b>不入 JWT</b>(防泄露) */
    private String password;
    /** 用户状态;决定 4 个 isXxx 方法返回值(ACTIVE / INACTIVE / BANNED / DELETED) */
    private UserStatus status;
    /** 角色列表(枚举);{@link #getAuthorities()} 转 SimpleGrantedAuthority 时自动加 "ROLE_" 前缀 */
    private List<Role> roles;

    /**
     * 把 {@link #roles} 转换为 Spring Security 的 {@link GrantedAuthority} 集合。
     * <p>仅用于<b>密码登录</b>路径(DaoAuthenticationProvider);JWT 鉴权路径
     * 由 {@code JwtAuthenticationFilter} 直接从 Redis 拉角色(不走这里)。
     *
     * <p>⚠️ 必须带 "ROLE_" 前缀:Spring Security 6 的 {@code hasRole('X')}
     * 期望 authority = {@code "ROLE_X"};不带前缀时 {@code @PreAuthorize("hasRole('ADMIN')")}
     * 永远不匹配(AGENTS.md 经验法则 8.2 历史踩坑点)。
     *
     * <p>roles 为 null 时本方法会 NPE——由 UserDetailsServiceImpl 保证 roles 非空。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                // 必须带 "ROLE_" 前缀 —— Spring Security 的 hasRole('X') 期望 authority = "ROLE_X"
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toList());
    }

    /**
     * 账户是否未过期。
     * <p>DELETED = 过期(注销视为账户过期);其他状态都视为未过期。
     *
     * <p>⚠️ 改进点:按 AGENTS.md 软删约定,真正的"过期"应判 {@code user.deletedAt != null},
     * 而非 status == DELETED(已 deprecated);已软删但 status 仍 ACTIVE 的用户本方法会误判未过期。
     */
    @Override
    public boolean isAccountNonExpired() {
        return status != UserStatus.DELETED;  // 已注销 = 过期
    }

    /**
     * 账户是否未锁定。
     * <p>BANNED = 锁定(管理员封禁);其他状态都视为未锁定。
     * <p>AuthenticationManager 会在此处抛 {@code LockedException},
     * 由 {@code AuthController.login} catch 后统一返 INVALID_CREDENTIALS(防账号枚举)。
     */
    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.BANNED;   // 禁用 = 锁定
    }

    /**
     * 凭证是否未过期。固定返 true —— 因为 token 时效由 JWT 自身的 exp claim 管控,
     * 比 UserDetails 的"凭证过期"概念更精确;这里简化处理。
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;  // 凭证永不过期
    }

    /**
     * 账户是否启用。
     * <p>只有 {@link UserStatus#ACTIVE} 视为启用;其他状态(INACTIVE / BANNED / DELETED)都返 false。
     * <p>未激活账号会抛 {@code DisabledException};BANNED 在 {@link #isAccountNonLocked}
     * 先抛 {@code LockedException},本方法不会再被调用(已认证失败)。
     */
    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE; // 账户启用，status为1表示正常
    }
}
