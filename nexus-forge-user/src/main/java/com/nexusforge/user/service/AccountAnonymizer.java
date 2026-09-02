package com.nexusforge.user.service;

import com.nexusforge.enums.UserStatus;
import com.nexusforge.file.FileClient;
import com.nexusforge.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * PII 匿名化工具 —— 注销用户时把可识别信息(用户名 / 邮箱 / 昵称 / 手机 / 头像 / 密码)
 * 全部擦除,保留审计需要的非 PII 字段(id / 角色 / plan_quota_override)。
 *
 * <p>不可逆:恢复时只能清 {@code deleted_at} + 改 status,无法找回原用户名 / 邮箱;
 * 用户恢复后必须走"忘记密码"流程重设凭证。</p>
 *
 * <p>设计要点:</p>
 * <ul>
 *   <li>{@code avatar_key} 走 {@link FileClient#delete} 删对象存储(失败仅 log,不阻塞)</li>
 *   <li>{@code email} 改为 {@code deleted-{id}@deleted.local} —— 释放原邮箱(避免泄漏);
 *       同时保持 {@code users.email} UNIQUE 约束不被打破</li>
 *   <li>{@code password} 改为 random bcrypt —— 防止旧密码 + 旧 refresh 登录</li>
 *   <li>角色 / quota override 保留 —— 后续 quota 计费 / 合规追溯仍需</li>
 *   <li>调用方必须 {@code @Transactional} 包裹(以保证 {@code save} + {@code FileClient.delete} 整体回滚语义,
 *       实际:对象存储删除不可回滚,所以 FileClient 失败仅 log 不抛)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountAnonymizer {

    private final FileClient fileClient;
    private final PasswordEncoder passwordEncoder;

    /**
     * 匿名化用户实体(原地修改,不 save —— 调用方负责持久化)
     *
     * @param user 要匿名化的用户(必须是已查询出来的 managed entity)
     */
    public void anonymize(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        Long userId = user.getId();

        // 1. 删头像对象(失败仅 log,不阻塞)
        String oldAvatarKey = user.getAvatarKey();
        if (oldAvatarKey != null && !oldAvatarKey.isBlank()) {
            try {
                fileClient.delete(oldAvatarKey);
            } catch (Exception e) {
                log.warn("[anonymize] failed to delete avatar object userId={} key={}: {}",
                        userId, oldAvatarKey, e.getMessage());
            }
        }

        // 2. PII 擦除
        user.setUsername("deleted-" + userId);
        user.setEmail("deleted-" + userId + "@deleted.local");
        user.setNickname("已注销用户");
        user.setPhone(null);
        user.setAvatarUrl(null);
        user.setAvatarKey(null);
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));

        // 3. status = BANNED 临时禁用,防止旧 refresh 登录(此处先不改 deleted_at,
        //    由调用方在 audit log 写入后调 repo.delete(entity) 走 @SQLDelete 写 deleted_at)
        user.setStatus(UserStatus.BANNED);
    }
}
