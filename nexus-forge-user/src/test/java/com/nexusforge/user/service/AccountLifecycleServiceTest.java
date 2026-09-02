package com.nexusforge.user.service;

import com.nexusforge.audit.AuditEvent;
import com.nexusforge.audit.AuditLogger;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.enums.UserStatus;
import com.nexusforge.event.UserBannedEvent;
import com.nexusforge.event.UserDataDeletionEvent;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.lock.DistributedLockTemplate;
import com.nexusforge.user.AccountLifecycleProperties;
import com.nexusforge.user.dto.ConfirmDeletionDto;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.enums.AccountActorRole;
import com.nexusforge.user.enums.AccountLifecycleAction;
import com.nexusforge.user.notification.UserDeletionMailer;
import com.nexusforge.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AccountLifecycleService} 单元测试 —— Mockito 隔离所有依赖,验证
 * ban / unban 流程;delete / restore / purge 留到后续 commit 验证。
 */
class AccountLifecycleServiceTest {

    private UserRepository userRepository;
    private UserRoleProvider userRoleProvider;
    private AccountAnonymizer accountAnonymizer;
    private ApplicationEventPublisher eventPublisher;
    private AuditLogger<AccountLifecycleAction> auditLogger;
    private AccountLifecycleProperties props;
    private PasswordEncoder passwordEncoder;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private UserDeletionMailer deletionMailer;
    private DistributedLockTemplate lockTemplate;
    private AccountLifecycleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        userRoleProvider = mock(UserRoleProvider.class);
        accountAnonymizer = mock(AccountAnonymizer.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        auditLogger = mock(AuditLogger.class);
        passwordEncoder = mock(PasswordEncoder.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        deletionMailer = mock(UserDeletionMailer.class);
        lockTemplate = mock(DistributedLockTemplate.class);
        props = new AccountLifecycleProperties();

        when(redis.opsForValue()).thenReturn(ops);
        // lockTemplate 直接调 supplier 拿值(单测不验证锁行为,锁路径在
        // DistributedLockTemplate 单测覆盖;这里只验委派 + 异常映射)
        when(lockTemplate.<Void>lock(anyString(), any(Duration.class), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> sup = inv.getArgument(2);
                    return sup.get();
                });

        service = new AccountLifecycleService(userRepository, userRoleProvider,
                accountAnonymizer, eventPublisher, auditLogger, props,
                passwordEncoder, redis, deletionMailer, lockTemplate);
    }

    private User activeUser(Long id) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setEmail("u" + id + "@x.com");
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    // ====================== ban ======================

    @Test
    @DisplayName("ban:status=BANNED + evict 角色 + 发 UserBannedEvent + 写审计")
    void ban_updates_status_clears_role_emits_event_and_audits() {
        User u = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.ban(1L, 99L, "test reason");

        assertThat(u.getStatus()).isEqualTo(UserStatus.BANNED);
        verify(userRepository).save(u);
        verify(userRoleProvider, times(1)).evict(1L);

        // 事件
        ArgumentCaptor<UserBannedEvent> evCap = ArgumentCaptor.forClass(UserBannedEvent.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        assertThat(evCap.getValue().userId()).isEqualTo(1L);

        // 审计
        ArgumentCaptor<AuditEvent<AccountLifecycleAction>> auditCap =
                ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(auditCap.capture());
        AuditEvent<AccountLifecycleAction> audit = auditCap.getValue();
        assertThat(audit.userId()).isEqualTo(1L);
        assertThat(audit.action()).isEqualTo(AccountLifecycleAction.BAN);
        assertThat(audit.actorId()).isEqualTo(99L);
        assertThat(audit.actorRole()).isEqualTo(AccountActorRole.ADMIN.name());
        assertThat(audit.reason()).isEqualTo("test reason");
    }

    @Test
    @DisplayName("ban:用户不存在 → 2001 USER_NOT_FOUND")
    void ban_user_not_found_throws() {
        when(userRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ban(2L, 99L, "x"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.USER_NOT_FOUND.getCode());

        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ====================== unban ======================

    @Test
    @DisplayName("unban:status=ACTIVE + evict 角色 + 写审计 + 不发踢 refresh 事件")
    void unban_restores_status() {
        User u = activeUser(3L);
        u.setStatus(UserStatus.BANNED);
        when(userRepository.findById(3L)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.unban(3L, 99L, "appeal granted");

        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userRoleProvider, times(1)).evict(3L);
        // unban 不踢 refresh —— 用户被封期间 refresh 早已失效
        verify(eventPublisher, never()).publishEvent(any(UserBannedEvent.class));

        ArgumentCaptor<AuditEvent<AccountLifecycleAction>> auditCap =
                ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(auditCap.capture());
        assertThat(auditCap.getValue().action()).isEqualTo(AccountLifecycleAction.UNBAN);
    }

    // ====================== requestDeletion ======================

    @Test
    @DisplayName("requestDeletion:密码对 + 限流通过 + 存 code hash + 发邮件 + 写审计")
    void request_deletion_persists_code_and_sends_mail() {
        User u = activeUser(1L);
        u.setEmail("alice@example.com");
        u.setPassword("ENCODED_OLD");
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(any(CharSequence.class), anyString())).thenReturn(true);
        when(ops.increment(anyString())).thenReturn(1L);

        service.requestDeletion(1L, "oldPass123");

        // 写 code hash(TTL = deleteCodeTtlMinutes = 5 min,验证 setDuration)
        verify(ops).set(anyString(), anyString(), eq(Duration.ofMinutes(5)));
        // 发邮件
        verify(deletionMailer, times(1)).sendDeleteConfirmation(eq("alice@example.com"), anyString(), eq(5));
        // 写审计
        ArgumentCaptor<AuditEvent<AccountLifecycleAction>> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo(AccountLifecycleAction.DELETE_REQUEST);
        assertThat(cap.getValue().actorRole()).isEqualTo(AccountActorRole.SELF.name());
    }

    @Test
    @DisplayName("requestDeletion:密码错 → 2011 OLD_PASSWORD_INCORRECT")
    void request_deletion_wrong_password_throws() {
        User u = activeUser(1L);
        u.setPassword("ENCODED");
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("WRONG", "ENCODED")).thenReturn(false);

        assertThatThrownBy(() -> service.requestDeletion(1L, "WRONG"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.OLD_PASSWORD_INCORRECT.getCode());

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        verify(deletionMailer, never()).sendDeleteConfirmation(anyString(), anyString(), any(Integer.class));
    }

    @Test
    @DisplayName("requestDeletion:60s 内重复申请 → 2015 RESET_CODE_SEND_TOO_FREQUENT")
    void request_deletion_rate_limited() {
        User u = activeUser(1L);
        u.setPassword("ENCODED");
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(any(CharSequence.class), anyString())).thenReturn(true);
        when(ops.increment(anyString())).thenReturn(2L);  // 第二次,current=2 > max=1

        assertThatThrownBy(() -> service.requestDeletion(1L, "pwd"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_SEND_TOO_FREQUENT.getCode());

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        verify(deletionMailer, never()).sendDeleteConfirmation(anyString(), anyString(), any(Integer.class));
    }

    @Test
    @DisplayName("requestDeletion:锁冲突(并发)→ 2015 RESET_CODE_SEND_TOO_FREQUENT")
    void request_deletion_lock_conflict() {
        // 模拟锁拿不到 — lockTemplate 内部 supplier.get() 抛 LockAcquireException
        when(lockTemplate.<Void>lock(anyString(), any(Duration.class), any()))
                .thenThrow(new com.nexusforge.lock.LockAcquireException("busy"));

        assertThatThrownBy(() -> service.requestDeletion(1L, "pwd"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_SEND_TOO_FREQUENT.getCode());

        // 锁冲突 → 不进 doRequestDeletion,userRepository / redis 都不调
        verify(userRepository, never()).findById(any());
        verify(ops, never()).increment(anyString());
    }

    @Test
    @DisplayName("requestDeletion:锁 key 是 'delete:request:{userId}'")
    void request_deletion_lock_key() {
        User u = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("pwd", u.getPassword())).thenReturn(true);
        when(ops.increment(anyString())).thenReturn(1L);

        service.requestDeletion(1L, "pwd");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(lockTemplate, times(1)).lock(
                keyCaptor.capture(), any(Duration.class), any());
        assertThat(keyCaptor.getValue()).isEqualTo("delete:request:1");
    }

    // ====================== confirmDeletion ======================

    @Test
    @DisplayName("confirmDeletion:正确 code + 擦除 PII + 软删 + 踢 refresh + 通知 ai + 写审计")
    void confirm_deletion_full_path() {
        User u = activeUser(7L);
        u.setEmail("alice@example.com");
        String code = "123456";
        String codeHash = AccountLifecycleService.sha256Hex(code);

        when(ops.increment(anyString())).thenReturn(1L);
        when(ops.get(anyString())).thenReturn(codeHash);
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(u));
        doNothing().when(accountAnonymizer).anonymize(u);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmDeletion(new ConfirmDeletionDto("alice@example.com", code));

        // PII 擦除(实际由 Anonymizer 改字段)
        verify(accountAnonymizer, times(1)).anonymize(u);
        // 软删走 @SQLDelete
        verify(userRepository, times(1)).delete(u);
        // 踢 refresh
        verify(eventPublisher, times(1)).publishEvent(any(UserBannedEvent.class));
        // 通知 ai 模块
        verify(eventPublisher, times(1)).publishEvent(any(UserDataDeletionEvent.class));
        // 写审计
        ArgumentCaptor<AuditEvent<AccountLifecycleAction>> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo(AccountLifecycleAction.DELETE_CONFIRM);
        // 发通知邮件(带 restoreUrl)
        verify(deletionMailer, times(1)).sendDeletedNotice(eq("alice@example.com"), anyString());
    }

    @Test
    @DisplayName("confirmDeletion:错误 code → 2013 RESET_CODE_INVALID")
    void confirm_deletion_wrong_code_throws() {
        when(ops.increment(anyString())).thenReturn(1L);
        when(ops.get(anyString())).thenReturn(AccountLifecycleService.sha256Hex("999999"));

        assertThatThrownBy(() -> service.confirmDeletion(
                new ConfirmDeletionDto("alice@example.com", "000000")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_INVALID.getCode());

        verify(accountAnonymizer, never()).anonymize(any());
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("confirmDeletion:attempts > max → 2014 + 清 code/attempts")
    void confirm_deletion_attempts_overflow_rejects_and_clears() {
        when(ops.increment(anyString())).thenReturn(6L);  // 超过 max=5

        assertThatThrownBy(() -> service.confirmDeletion(
                new ConfirmDeletionDto("alice@example.com", "000000")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_TOO_MANY_ATTEMPTS.getCode());

        // 删 codeKey + attemptsKey 共 2 次
        verify(redis, times(2)).delete(anyString());
    }

    @Test
    @DisplayName("confirmDeletion:code 已过期(redis 无 storedHash) → 2013")
    void confirm_deletion_expired_code_throws() {
        when(ops.increment(anyString())).thenReturn(1L);
        when(ops.get(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.confirmDeletion(
                new ConfirmDeletionDto("alice@example.com", "123456")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_INVALID.getCode());
    }

    // ====================== restoreFromToken ======================

    @Test
    @DisplayName("restoreFromToken:有效 token + UPDATE 成功 → 清 deleted_at + 删 token + 写审计")
    void restore_valid_token_unblocks_user() {
        String token = "a".repeat(64);
        when(ops.get(anyString())).thenReturn("7");

        // 反射注入 entityManager
        jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("userId"), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", em);

        service.restoreFromToken(token);

        verify(em).createNativeQuery(org.mockito.ArgumentMatchers.contains("UPDATE users SET deleted_at = NULL"));
        verify(query).executeUpdate();
        // token 用完即删
        verify(redis, times(1)).delete(anyString());
        // 写审计
        ArgumentCaptor<AuditEvent<AccountLifecycleAction>> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo(AccountLifecycleAction.RESTORE);
    }

    @Test
    @DisplayName("restoreFromToken:token 在 Redis 找不到 → 2013")
    void restore_token_not_found_throws() {
        when(ops.get(anyString())).thenReturn(null);

        jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", em);

        assertThatThrownBy(() -> service.restoreFromToken("missing-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_INVALID.getCode());

        verify(em, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("restoreFromToken:UPDATE 0 行(用户已被定时真删) → 2001 + 清 token")
    void restore_user_already_purged_throws() {
        when(ops.get(anyString())).thenReturn("7");

        jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("userId"), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);  // 没人被更新
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", em);

        assertThatThrownBy(() -> service.restoreFromToken("a".repeat(64)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.USER_NOT_FOUND.getCode());

        verify(redis, times(1)).delete(anyString());  // 清 token
    }

    // ====================== purgeExpiredDeletions ======================

    @Test
    @DisplayName("purgeExpiredDeletions:调 EntityManager.createNativeQuery 真删过期 user")
    void purge_expired_deletions_invokes_native_delete() {
        jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.Query query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("days"), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(3);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", em);

        int n = service.purgeExpiredDeletions();

        assertThat(n).isEqualTo(3);
        verify(em).createNativeQuery(org.mockito.ArgumentMatchers.contains("DELETE FROM users WHERE deleted_at IS NOT NULL"));
        verify(query).setParameter("days", props.getDeletionGracePeriodDays());
    }
}
