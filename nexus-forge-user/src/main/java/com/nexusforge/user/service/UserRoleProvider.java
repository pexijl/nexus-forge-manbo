package com.nexusforge.user.service;

import com.nexusforge.cache.CachedValueLoader;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 用户角色对外只读视图。
 *
 * <p>设计动机：</p>
 * <ul>
 *   <li>auth 模块的 PermissionLoader 需要"按 userId 拿角色"，但又不能反向依赖 user 业务代码</li>
 *   <li>user 模块自己最清楚角色从哪来、怎么失效，所以暴露一个最小只读接口</li>
 *   <li>缓存机制走 common.CachedValueLoader，user 不写缓存工具</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserRoleProvider {

    private static final String PREFIX = "auth:roles:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final CachedValueLoader cache;

    /**
     * 加载用户的角色名列表（逗号分隔的字符串，空字符串表示"用户不存在或无角色"）
     */
    @Transactional(readOnly = true)
    public String loadRolesCsv(Long userId) {
        return cache.loadOrCompute(
                PREFIX + userId,
                TTL,
                () -> userRepository.findById(userId)
                        .map(User::getRoles)
                        .map(roles -> roles.stream()
                                .map(Enum::name)
                                .reduce((a, b) -> a + "," + b)
                                .orElse(""))
                        .orElse("")
        );
    }

    /**
     * 角色变更/封禁时调用
     */
    public void evict(Long userId) {
        cache.evict(PREFIX + userId);
    }
}