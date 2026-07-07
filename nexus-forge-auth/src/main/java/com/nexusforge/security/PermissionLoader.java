package com.nexusforge.security;

import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import com.nexusforge.user.service.UserRoleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 从 UserRoleProvider 加载当前请求用户的角色。
 *
 * <p>这个类只做一件事：把 provider 返回的 csv 拆成 List&lt;String&gt;。</p>
 * <p>数据来源和缓存细节都在 user 模块，这里不关心。</p>
 */
@Component
@RequiredArgsConstructor
public class PermissionLoader {

    private final UserRoleProvider userRoleProvider;

    public List<String> loadRoles(Long userId) {
        String csv = userRoleProvider.loadRolesCsv(userId);
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.asList(csv.split(","));
    }

    /** 角色变更/封禁时调用 —— 现在由 user 模块自己触发，不经过 auth */
    public void evict(Long userId) {
        userRoleProvider.evict(userId);
    }
}