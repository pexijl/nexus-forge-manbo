package com.nexusforge.user.controller;

import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.user.dto.BanUserDto;
import com.nexusforge.user.dto.UnbanUserDto;
import com.nexusforge.user.entity.AccountLifecycleLog;
import com.nexusforge.user.enums.AccountActorRole;
import com.nexusforge.user.enums.AccountLifecycleAction;
import com.nexusforge.user.repository.AccountLifecycleLogRepository;
import com.nexusforge.user.service.AccountLifecycleService;
import com.nexusforge.user.vo.AccountLifecycleLogVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminUserLifecycleController} 单元测试 —— Mockito 隔离,只验证 controller
 * 委派 Service + 拼装 VO;{@code @PreAuthorize} 鉴权验证留给 commit 5 集成测试
 * (需要 ADMIN 真实 token,MockMvc 走完整 Spring 上下文)。
 */
class AdminUserLifecycleControllerTest {

    private AccountLifecycleService service;
    private AccountLifecycleLogRepository logRepository;
    private AdminUserLifecycleController controller;

    @BeforeEach
    void setUp() {
        service = mock(AccountLifecycleService.class);
        logRepository = mock(AccountLifecycleLogRepository.class);
        controller = new AdminUserLifecycleController(service, logRepository);

        // 模拟已认证的 admin(避免 @AuthenticationPrincipal 解析 NPE)
        UserPrincipal admin = new UserPrincipal(99L, "admin");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        admin, "n/a", Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
    }

    @Test
    @DisplayName("ban:委派 service.ban(userId, adminId, reason)")
    void ban_delegates_to_service() {
        Result<Void> r = controller.ban(1L, new BanUserDto("违规 spam"));
        assertThat(r.getCode()).isEqualTo(Result.success().getCode());
        verify(service, times(1)).ban(eq(1L), eq(99L), eq("违规 spam"));
    }

    @Test
    @DisplayName("ban:Body 为空也接受(写 null reason)")
    void ban_with_null_body_uses_null_reason() {
        Result<Void> r = controller.ban(1L, null);
        assertThat(r.getCode()).isEqualTo(Result.success().getCode());
        verify(service, times(1)).ban(eq(1L), eq(99L), eq(null));
    }

    @Test
    @DisplayName("unban:委派 service.unban(userId, adminId, reason)")
    void unban_delegates_to_service() {
        Result<Void> r = controller.unban(2L, new UnbanUserDto("申诉通过"));
        assertThat(r.getCode()).isEqualTo(Result.success().getCode());
        verify(service, times(1)).unban(eq(2L), eq(99L), eq("申诉通过"));
    }

    @Test
    @DisplayName("listUserLifecycle:查某 user 全部事件,转 VO 列表")
    void list_user_lifecycle_returns_vo() {
        AccountLifecycleLog log = new AccountLifecycleLog();
        log.setId(1L);
        log.setUserId(5L);
        log.setAction(AccountLifecycleAction.BAN);
        log.setActorId(99L);
        log.setActorRole(AccountActorRole.ADMIN);
        log.setReason("test");
        log.setMetadata(Map.of("k", "v"));
        log.setCreatedAt(OffsetDateTime.now());
        when(logRepository.findByUserIdOrderByCreatedAtDesc(5L))
                .thenReturn(List.of(log));

        Result<List<AccountLifecycleLogVo>> r = controller.listUserLifecycle(5L);

        assertThat(r.getCode()).isEqualTo(Result.success().getCode());
        assertThat(r.getData()).hasSize(1);
        assertThat(r.getData().get(0).action()).isEqualTo(AccountLifecycleAction.BAN);
        assertThat(r.getData().get(0).actorRole()).isEqualTo(AccountActorRole.ADMIN);
    }

    @Test
    @DisplayName("listLifecycleByAction:分页查询,转 PageResult<VO>")
    void list_lifecycle_by_action_returns_page() {
        AccountLifecycleLog log = new AccountLifecycleLog();
        log.setId(1L);
        log.setUserId(5L);
        log.setAction(AccountLifecycleAction.BAN);
        log.setActorRole(AccountActorRole.ADMIN);
        log.setCreatedAt(OffsetDateTime.now());
        Page<AccountLifecycleLog> page = new PageImpl<>(List.of(log), PageRequest.of(0, 20), 1);
        when(logRepository.findByActionOrderByCreatedAtDesc(eq(AccountLifecycleAction.BAN), any()))
                .thenReturn(page);

        Result<?> r = controller.listLifecycleByAction(AccountLifecycleAction.BAN, 1, 20);

        assertThat(r.getCode()).isEqualTo(Result.success().getCode());
        assertThat(r.getData()).isNotNull();
    }
}
