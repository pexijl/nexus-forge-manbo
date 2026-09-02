package com.nexusforge.security;

import com.nexusforge.user.service.UserRoleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 把 {@link UserRoleProvider} 返回的 CSV 拆成 {@code List<String>},供 JWT 鉴权链路使用。
 *
 * <p><b>角色定位</b>:本类是 auth 模块调用 user 模块的"薄包装"——auth 模块需要"按 userId 拿角色",
 * 但<b>不能反向依赖</b> user 业务代码;user 模块通过 SPI({@code UserRoleProvider})暴露最小只读接口,
 * 本类负责 CSV → List 的格式转换。
 *
 * <p><b>数据来源</b>:
 * <ul>
 *   <li>Provider 内部用 Redis 缓存(Redis key {@code auth:roles:<userId>},见 {@code RedisCleaner}),
 *       缓存命中直接返回;未命中查 user_role 表后回填,带 TTL(具体由 UserRoleProviderImpl 决定)</li>
 *   <li>缓存细节对 auth 模块透明,本类不感知</li>
 * </ul>
 *
 * <p><b>唯一调用方</b>:{@code com.nexusforge.filter.JwtAuthenticationFilter} 每个请求的
 * step 7 拉角色用;登录路径(LoginUser)不走这里。
 *
 * <p><b>技术债</b>:
 * <ul>
 *   <li><b>死 import</b>:{@code StringRedisTemplate} / {@code User} / {@code UserRepository} —
 *       早期版本直接查 Redis/DB,后改为走 Provider;import 未清理</li>
 *   <li><b>死方法</b>:{@link #evict(Long)} 全仓无任何调用方,角色失效由 user 模块直接
 *       调 {@code UserRoleProvider.evict}(见下方 Javadoc)</li>
 * </ul>
 *
 * @see com.nexusforge.user.service.UserRoleProvider CSV 来源 + Redis 缓存
 * @see com.nexusforge.user.service.UserRoleProviderImpl 缓存实现
 * @see com.nexusforge.filter.JwtAuthenticationFilter 唯一调用方
 */
@Component
@RequiredArgsConstructor
public class PermissionLoader {

    private final UserRoleProvider userRoleProvider;

    /**
     * 加载指定用户的角色列表。
     *
     * <p>调用链:Provider 读 Redis 缓存 → 未命中查 DB → 回填缓存 → 返 csv →
     * 本方法按逗号拆成 {@code List<String>}。
     *
     * <p><b>边界</b>:
     * <ul>
     *   <li>{@code csv == null} 或空字符串 → 返 {@link List#of()} 空集合(而非 null,避免下游 NPE)</li>
     *   <li>{@code split(",")} 不带 limit → Java 默认去掉尾部空串:{"ADMIN,,"} → {"ADMIN"}</li>
     * </ul>
     *
     * @param userId 用户主键
     * @return 角色列表;空集合而非 null(供 {@code JwtAuthenticationFilter} 直接 stream 转 authority)
     */
    public List<String> loadRoles(Long userId) {
        String csv = userRoleProvider.loadRolesCsv(userId);
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.asList(csv.split(","));
    }

    /**
     * 主动失效用户的角色缓存。
     *
     * <p><b>⚠️ 当前是死方法</b>(grep 全仓无任何调用方):
     * 角色变更 / 封禁 / 注销时由 user 模块<b>直接</b>调 {@link UserRoleProvider#evict},
     * 不走 auth 模块(避免反向依赖)。
     *
     * <p>本方法保留是因为:
     * <ul>
     *   <li>未来若 auth 模块需要主动失效(例如角色管理 API),可复用此透传</li>
     *   <li>保留公开 API 形状,避免调用方未来切换到 auth 调用时改动签名</li>
     * </ul>
     *
     * <p>如果确认永远用不到,可删除本方法 + 移除 Provider 的 {@code evict} 公开 API 同步收紧。
     *
     * @param userId 用户主键
     */
    public void evict(Long userId) {
        userRoleProvider.evict(userId);
    }
}