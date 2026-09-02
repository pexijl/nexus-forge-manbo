package com.nexusforge.user.service;

import com.nexusforge.audit.AuditEvent;
import com.nexusforge.audit.AuditLogger;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.event.UserBannedEvent;
import com.nexusforge.event.UserDataDeletionEvent;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.user.AccountLifecycleProperties;
import com.nexusforge.user.dto.ConfirmDeletionDto;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.enums.AccountActorRole;
import com.nexusforge.user.enums.AccountLifecycleAction;
import com.nexusforge.user.notification.UserDeletionMailer;
import com.nexusforge.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * 账号生命周期主服务 —— 集中"封禁 / 解封 / 注销 / 恢复 / 真删"语义。
 *
 * <p>设计动机:账号状态变更涉及用户实体修改、auth 模块 token 失效、各业务模块
 * 数据清理(ai / file / ...)、审计日志写入。把这些副作用集中在一个 service,
 * 比散在 {@code UserService.updateXxx} 里可控得多。</p>
 *
 * <p><b>事件流</b>:</p>
 * <ul>
 *   <li>{@code ban} / {@code unban} / {@code deleteConfirm} 都 publish
 *       {@link UserBannedEvent}(语义上都是"踢 refresh"——auth 模块已监听)</li>
 *   <li>{@code deleteConfirm} 还会 publish {@link com.nexusforge.event.UserDataDeletionEvent}
 *       通知 ai 模块删自己数据(commit 2 实装)</li>
 *   <li>所有方法都写 {@code account_lifecycle_log} 审计行</li>
 * </ul>
 *
 * <p>本类是 commit 1 的骨架,真实实现按 commit 顺序补:</p>
 * <ul>
 *   <li>commit 1: {@link #ban} 重构自 UserService.banUser</li>
 *   <li>commit 2: 注销 request/confirm 流程 + 邮件 + 验证码</li>
 *   <li>commit 3: 一次性恢复 token + 撤销注销</li>
 *   <li>commit 4: 解封(权限校验放在 Controller,本方法不做权限)</li>
 *   <li>commit 5: expire-deletions 定时真删</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLifecycleService {

    private final UserRepository userRepository;
    private final UserRoleProvider userRoleProvider;
    private final AccountAnonymizer accountAnonymizer;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogger<AccountLifecycleAction> auditLogger;
    private final AccountLifecycleProperties props;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final UserDeletionMailer deletionMailer;
    private final com.nexusforge.lock.DistributedLockTemplate lockTemplate;

    /**
     * EntityManager 注入(非 final,因 @PersistenceContext 走运行时注入)。
     * restoreFromToken / purgeExpiredDeletions 用它走原生 SQL 绕开 @SQLRestriction。
     */
    @PersistenceContext
    private EntityManager entityManager;

    private final SecureRandom random = new SecureRandom();

    private static final String NS_CODE = "pwd:delete:code:";
    private static final String NS_ATTEMPTS = "pwd:delete:attempts:";
    private static final String NS_RATE = "pwd:delete:rate:";
    private static final String NS_RESTORE = "pwd:restore:token:";

    // ====================== ban / unban ======================

    /**
     * 封禁用户 —— 管理员强制;status=BANNED + 角色缓存清理 + 踢 refresh + 审计。
     *
     * <p>Controller 层必须做角色校验({@code @PreAuthorize("hasRole('ADMIN')")}),
     * 本方法不做权限判断(便于 commit 5 测试直接调)。</p>
     *
     * @param userId  被封禁用户
     * @param actorId 操作管理员 id
     * @param reason  封禁理由(可空,会写入审计)
     * @throws BusinessException {@code 2001 USER_NOT_FOUND} 用户不存在
     */
    @Transactional
    public void ban(Long userId, Long actorId, String reason) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));

        user.setStatus(com.nexusforge.enums.UserStatus.BANNED);
        userRepository.save(user);

        userRoleProvider.evict(userId);   // 角色缓存清理
        eventPublisher.publishEvent(new UserBannedEvent(userId));   // 触发 auth 踢 refresh

        auditLogger.log(AuditEvent.<AccountLifecycleAction>builder()
                .userId(userId)
                .action(AccountLifecycleAction.BAN)
                .actorId(actorId)
                .actorRole(AccountActorRole.ADMIN.name())
                .reason(reason)
                .metadata(Map.of("statusFrom", user.getStatus().name(), "statusTo", "BANNED"))
                .build());

        log.info("[lifecycle] user banned userId={} actorId={}", userId, actorId);
    }

    /**
     * 解封用户 —— 管理员撤销;status=ACTIVE + 角色缓存清理 + 审计。
     *
     * <p>暂不踢 refresh:被封禁期间 refresh 已被踢,用户无活跃 token,
     * 解封后用户需重新登录拿到新 token。</p>
     */
    @Transactional
    public void unban(Long userId, Long actorId, String reason) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));

        user.setStatus(com.nexusforge.enums.UserStatus.ACTIVE);
        userRepository.save(user);

        userRoleProvider.evict(userId);

        auditLogger.log(AuditEvent.<AccountLifecycleAction>builder()
                .userId(userId)
                .action(AccountLifecycleAction.UNBAN)
                .actorId(actorId)
                .actorRole(AccountActorRole.ADMIN.name())
                .reason(reason)
                .build());

        log.info("[lifecycle] user unbanned userId={} actorId={}", userId, actorId);
    }

    // ====================== delete / restore ======================

    /**
     * 申请注销 —— 验证密码,生成 6 位验证码,发邮件。
     *
     * <p>Redis 键设计(命名空间 {@code pwd:delete:*,}与密码重置隔离):</p>
     * <ul>
     *   <li>{@code pwd:delete:code:{emailHash}} —— SHA-256(验证码),TTL = deleteCodeTtlMinutes</li>
     *   <li>{@code pwd:delete:attempts:{emailHash}} —— 验证失败计数,首次 INCR 设 TTL</li>
     *   <li>{@code pwd:delete:rate:{emailHash}} —— 邮箱维度限流 60s 内 1 次</li>
     * </ul>
     *
     * <p>并发防护:{@code lockTemplate.lock("delete:request:" + userId, 10s)} —
     * 同一 user 并发申请序列化,即使 Redis 限流键因 TTL 过期被刷掉,锁层
     * 仍能挡住。拿不到锁抛 {@code 2015 RESET_CODE_SEND_TOO_FREQUENT}(同
     * 限流码,前端体验一致)。</p>
     *
     * @param userId  当前登录用户 id
     * @param password 当前密码(二次确认)
     * @throws BusinessException {@code 2011 OLD_PASSWORD_INCORRECT} 密码错
     *                            {@code 2015 RESET_CODE_SEND_TOO_FREQUENT} 并发 / 限流
     */
    public void requestDeletion(Long userId, String password) {
        String lockKey = "delete:request:" + userId;
        try {
            lockTemplate.lock(lockKey, Duration.ofSeconds(10), () -> {
                doRequestDeletion(userId, password);
                return null;
            });
        } catch (com.nexusforge.lock.LockAcquireException e) {
            // 锁冲突:并发 / 上次请求未完成 — 限流码兜底(用户体验同 rate limit)
            throw new BusinessException(ResultCode.RESET_CODE_SEND_TOO_FREQUENT);
        }
    }

    /**
     * requestDeletion 实际工作 —— 锁外层不直接加 @Transactional 是因为
     * 锁在事务里包,事务在锁释放前不会 commit,微窗口期别人可看到
     * PENDING 中间态。这里不加事务(本方法无多写),靠 Redis 单命令
     * 原子性(INCR / SET)保证一致性。
     */
    private void doRequestDeletion(Long userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ResultCode.OLD_PASSWORD_INCORRECT);
        }

        String email = user.getEmail();
        String emailHash = sha256Hex(email.toLowerCase().trim());

        // 邮箱维度限流 60s 内 1 次
        Long current = redis.opsForValue().increment(NS_RATE + emailHash);
        if (current != null && current == 1L) {
            redis.expire(NS_RATE + emailHash, Duration.ofSeconds(60));
        }
        if (current != null && current > 1) {
            throw new BusinessException(ResultCode.RESET_CODE_SEND_TOO_FREQUENT);
        }

        // 生成 + 存 code hash
        String code = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(NS_CODE + emailHash, sha256Hex(code),
                Duration.ofMinutes(props.getDeleteCodeTtlMinutes()));
        // attempts key 不预创建,confirm 时 INCR 出来

        try {
            deletionMailer.sendDeleteConfirmation(email, code, props.getDeleteCodeTtlMinutes());
        } catch (Exception e) {
            log.warn("[deletion] failed to send confirm email userId={}: {}", userId, e.getMessage());
        }

        // 写审计
        auditLogger.log(AuditEvent.<AccountLifecycleAction>builder()
                .userId(userId)
                .action(AccountLifecycleAction.DELETE_REQUEST)
                .actorId(userId)
                .actorRole(AccountActorRole.SELF.name())
                .metadata(Map.of("emailHash", emailHash.substring(0, 8)))
                .build());

        log.info("[deletion] code sent userId={} emailHash={}", userId, emailHash.substring(0, 8));
    }

    /**
     * 确认注销 —— 校验验证码,执行真删。
     *
     * <p>执行步骤:</p>
     * <ol>
     *   <li>INCR attempts,首次设 TTL,超过 deleteCodeMaxAttempts 清 code+attempts 抛 2014</li>
     *   <li>取 storedHash,null 抛 2013</li>
     *   <li>MessageDigest.isEqual 比对,不等抛 2013</li>
     *   <li>{@link AccountAnonymizer#anonymize} 原地修改 PII</li>
     *   <li>userRepository.save(user) 持久化 PII 擦除</li>
     *   <li>userRepository.delete(user) 走 @SQLDelete 写 deleted_at</li>
     *   <li>publish {@link UserBannedEvent} 触发 auth 模块踢 refresh</li>
     *   <li>publish {@link UserDataDeletionEvent} 触发 ai 模块真删对话/消息/用量</li>
     *   <li>删 Redis code+attempts</li>
     *   <li>写审计</li>
     *   <li>发"已注销"通知邮件(发到原邮箱,但 PII 已擦除无法登录)</li>
     * </ol>
     */
    @Transactional
    public void confirmDeletion(ConfirmDeletionDto dto) {
        String email = dto.email().trim().toLowerCase();
        String emailHash = sha256Hex(email);
        String attemptsKey = NS_ATTEMPTS + emailHash;
        String codeKey = NS_CODE + emailHash;

        // 1. 失败次数自增
        Long attempts = redis.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1L) {
            redis.expire(attemptsKey, Duration.ofMinutes(props.getDeleteCodeTtlMinutes()));
        }
        if (attempts != null && attempts > props.getDeleteCodeMaxAttempts()) {
            redis.delete(codeKey);
            redis.delete(attemptsKey);
            throw new BusinessException(ResultCode.RESET_CODE_TOO_MANY_ATTEMPTS);
        }

        // 2. 取 storedHash
        String storedHash = redis.opsForValue().get(codeKey);
        if (storedHash == null) {
            throw new BusinessException(ResultCode.RESET_CODE_INVALID);
        }

        // 3. 比对
        byte[] stored = storedHash.getBytes(StandardCharsets.UTF_8);
        byte[] input = sha256Hex(dto.code()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(stored, input)) {
            throw new BusinessException(ResultCode.RESET_CODE_INVALID);
        }

        // 4. 查 user —— 必须存在且 active(已注销的不能再注销)
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    log.warn("[deletion] code valid but user gone emailHash={}", emailHash);
                    return new BusinessException(ResultCode.RESET_CODE_INVALID);
                });
        Long userId = user.getId();

        // 5. PII 擦除 + 软删
        accountAnonymizer.anonymize(user);
        userRepository.save(user);            // 先持久化 PII 擦除
        userRepository.delete(user);          // 再走 @SQLDelete 写 deleted_at

        // 6. 清理 Redis
        redis.delete(codeKey);
        redis.delete(attemptsKey);

        // 7. 踢 refresh
        eventPublisher.publishEvent(new UserBannedEvent(userId));

        // 8. 通知业务模块真删自己的数据
        eventPublisher.publishEvent(new UserDataDeletionEvent(userId));

        // 9. 审计
        auditLogger.log(AuditEvent.<AccountLifecycleAction>builder()
                .userId(userId)
                .action(AccountLifecycleAction.DELETE_CONFIRM)
                .actorId(userId)
                .actorRole(AccountActorRole.SELF.name())
                .build());

        // 10. 生成恢复 token + 存 Redis + 通知邮件(发到原邮箱;PII 已擦除,但邮箱本身可触达)
        String restoreToken = generateRestoreToken();
        String tokenHash = sha256Hex(restoreToken);
        redis.opsForValue().set(NS_RESTORE + tokenHash, String.valueOf(userId),
                Duration.ofDays(props.getRestoreTokenTtlDays()));

        String restoreUrl = props.getRestoreBaseUrl() + "/restore?token=" + restoreToken;
        try {
            deletionMailer.sendDeletedNotice(email, restoreUrl);
        } catch (Exception e) {
            log.warn("[deletion] failed to send deleted notice userId={}: {}", userId, e.getMessage());
        }

        log.info("[deletion] account deleted userId={} restoreToken issued ttl={}d",
                userId, props.getRestoreTokenTtlDays());
    }

    // ====================== restore / purge(commit 3 / 5) ======================

    /**
     * 撤销注销 —— 公开端点,验证一次性 token,清 deleted_at + 改 status=ACTIVE。
     *
     * <p>不可恢复原 PII(username/email/phone/avatar/...),用户恢复后必须走
     * 密码重置流程重设凭证。Redis refresh 版本号也清掉(用户登录后自动
     * 拿新 refresh)。</p>
     *
     * <p>走 EntityManager 原生 SQL 绕开 @SQLRestriction
     * (参考 commit 5f368cf 的 ConversationService.restoreConversation 经验)。</p>
     */
    @Transactional
    public void restoreFromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.RESET_CODE_INVALID);
        }
        String tokenHash = sha256Hex(token);
        String redisVal = redis.opsForValue().get(NS_RESTORE + tokenHash);
        if (redisVal == null) {
            // token 不存在 / 已过期 / 已用
            throw new BusinessException(ResultCode.RESET_CODE_INVALID);
        }
        Long userId = Long.valueOf(redisVal);

        // 用原生 SQL 走纯 JDBC 通道
        int updated = entityManager.createNativeQuery(
                "UPDATE users SET deleted_at = NULL, status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = :userId AND deleted_at IS NOT NULL")
                .setParameter("userId", userId)
                .executeUpdate();
        if (updated == 0) {
            // 用户可能已被定时任务真删
            redis.delete(NS_RESTORE + tokenHash);
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 删 token(一次性)
        redis.delete(NS_RESTORE + tokenHash);

        // 写审计
        auditLogger.log(AuditEvent.<AccountLifecycleAction>builder()
                .userId(userId)
                .action(AccountLifecycleAction.RESTORE)
                .actorId(userId)
                .actorRole(AccountActorRole.SELF.name())
                .build());

        log.info("[deletion] account restored userId={}", userId);
    }

    /**
     * 定时清理已过期用户 —— 查 deleted_at < now - grace_period 真删。
     *
     * <p>由 {@link Scheduled} 触发(默认每天凌晨 3 点,见
     * {@link AccountLifecycleProperties#getExpireDeletionsCron()})。
     * 真删用 EntityManager 原生 SQL 绕开 @SQLRestriction。</p>
     *
     * <p>返回真删的 user 数(用于监控 / 日志)。</p>
     */
    @Scheduled(cron = "${account-lifecycle.expire-deletions-cron:0 0 3 * * *}")
    @Transactional
    public int purgeExpiredDeletions() {
        // 注意:这里假设 PII 已经在注销时清掉了(commit 2 的 AccountAnonymizer),
        // 真删只是物理移除 users 行,无 PII 风险;ai 数据在 commit 2 confirm 时
        // 通过 UserDataDeletionEvent 已真删,无需再清。
        int deleted = entityManager.createNativeQuery(
                "DELETE FROM users WHERE deleted_at IS NOT NULL " +
                "AND deleted_at < (CURRENT_TIMESTAMP - (:days || ' days')::interval)")
                .setParameter("days", props.getDeletionGracePeriodDays())
                .executeUpdate();
        if (deleted > 0) {
            log.info("[lifecycle-scheduler] hard-deleted {} expired users (grace={}d)",
                    deleted, props.getDeletionGracePeriodDays());
        }
        return deleted;
    }

    // ====================== helpers ======================

    /** 32 字节 SecureRandom 生成的恢复 token,转 64 位 hex 字符串 */
    String generateRestoreToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
