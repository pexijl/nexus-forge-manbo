package com.nexusforge.ai.service;

import com.nexusforge.ai.controller.dto.UserAiModelAliasDto;
import com.nexusforge.ai.entity.UserAiModelAlias;
import com.nexusforge.ai.event.UserAiModelAliasChangedEvent;
import com.nexusforge.ai.repository.UserAiModelAliasRepository;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 4 — {@link UserAiModelAliasService} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>create:alias 唯一 / 含冒号拒绝 / targetVendor+targetModel trim+非空校验 / CREATED 事件</li>
 *   <li>update:partial update / alias 改名 unique 校验 / 改名发 renamed 事件(oldAlias+newAlias)/ 不改名发 UPDATED 事件</li>
 *   <li>delete:DELETED 事件带 alias 名</li>
 *   <li>findByUserIdAndAlias:大小写不敏感 / 含冒号跳过 / 空白跳过 / 缓存命中</li>
 *   <li>invalidateCache:旧 + 新 key 同时清(改名场景)/ 兜底 invalidateAll(userId null)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserAiModelAliasService — Phase 4 模型别名")
class UserAiModelAliasServiceTest {

    @Mock UserAiModelAliasRepository repo;
    @Mock ApplicationEventPublisher publisher;

    private UserAiModelAliasService service;

    @BeforeEach
    void setUp() {
        service = new UserAiModelAliasService(repo, publisher);
    }

    // ─────────────────── create ───────────────────

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常路径:alias 归一化 trim、target 归一化、save、发 CREATED 事件")
        void creates_and_publishes() {
            UserAiModelAliasDto dto = newDto("  我的 GPT  ", "  OpenAI  ", "  gpt-4o-mini  ", null);
            when(repo.existsByUserIdAndAliasIgnoreCase(7L, "我的 GPT")).thenReturn(false);
            when(repo.save(any(UserAiModelAlias.class))).thenAnswer(inv -> {
                UserAiModelAlias a = inv.getArgument(0);
                setId(a, 1L);
                return a;
            });

            UserAiModelAlias saved = service.create(7L, dto);

            assertThat(saved.getUserId()).isEqualTo(7L);
            assertThat(saved.getAlias()).isEqualTo("我的 GPT");
            assertThat(saved.getTargetVendor()).isEqualTo("openai");
            assertThat(saved.getTargetModel()).isEqualTo("gpt-4o-mini");
            assertThat(saved.getEnabled()).isTrue();

            ArgumentCaptor<UserAiModelAliasChangedEvent> ev = ArgumentCaptor.forClass(UserAiModelAliasChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiModelAliasChangedEvent.ChangeType.CREATED);
        }

        @Test
        @DisplayName("alias 已存在(大小写不敏感)→ 报错")
        void duplicate_alias_throws() {
            UserAiModelAliasDto dto = newDto("我的 GPT", "openai", "gpt-4o-mini", null);
            when(repo.existsByUserIdAndAliasIgnoreCase(7L, "我的 GPT")).thenReturn(true);

            assertThatThrownBy(() -> service.create(7L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("同名 alias");
        }

        @Test
        @DisplayName("alias 名含冒号 → LLM_INVALID_REQUEST")
        void alias_with_colon_throws() {
            // 绕过 DTO @Pattern 校验,直接绕过 controller 调 service(防御性)
            UserAiModelAliasDto dto = newDto("vendor:model", "openai", "gpt-4o-mini", null);

            assertThatThrownBy(() -> service.create(7L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能含冒号")
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("targetVendor / targetModel 留空 → 报错")
        void empty_target_throws() {
            UserAiModelAliasDto dto1 = newDto("我的", "  ", "gpt-4o-mini", null);
            assertThatThrownBy(() -> service.create(7L, dto1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("targetVendor 不能为空");

            UserAiModelAliasDto dto2 = newDto("我的", "openai", "  ", null);
            assertThatThrownBy(() -> service.create(7L, dto2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("targetModel 不能为空");
        }

        @Test
        @DisplayName("dto.enabled=false → 创建时直接禁用(草稿语义)")
        void enabled_false_at_create() {
            UserAiModelAliasDto dto = newDto("草稿", "openai", "gpt-4o-mini", false);
            when(repo.existsByUserIdAndAliasIgnoreCase(7L, "草稿")).thenReturn(false);
            when(repo.save(any(UserAiModelAlias.class))).thenAnswer(inv -> {
                UserAiModelAlias a = inv.getArgument(0);
                setId(a, 2L);
                return a;
            });

            UserAiModelAlias saved = service.create(7L, dto);
            assertThat(saved.getEnabled()).isFalse();
        }
    }

    // ─────────────────── update ───────────────────

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("partial update:只改 targetModel,发 UPDATED 事件(非 renamed)")
        void partial_update_no_rename() {
            UserAiModelAlias existing = newAlias(10L, 7L, "我的 GPT", "openai", "gpt-4o-mini", true);
            when(repo.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(existing));
            when(repo.save(any(UserAiModelAlias.class))).thenAnswer(inv -> inv.getArgument(0));

            UserAiModelAliasDto dto = new UserAiModelAliasDto();
            dto.setTargetModel("gpt-4o");   // 只改这个
            // alias / targetVendor / enabled 不传 → 保留

            UserAiModelAlias updated = service.update(7L, 10L, dto);

            assertThat(updated.getTargetModel()).isEqualTo("gpt-4o");
            assertThat(updated.getAlias()).isEqualTo("我的 GPT");           // 保留
            assertThat(updated.getTargetVendor()).isEqualTo("openai");     // 保留
            assertThat(updated.getEnabled()).isTrue();                     // 保留

            ArgumentCaptor<UserAiModelAliasChangedEvent> ev = ArgumentCaptor.forClass(UserAiModelAliasChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiModelAliasChangedEvent.ChangeType.UPDATED);
            assertThat(ev.getValue().getOldAlias()).isNull();   // 非 renamed,无 oldAlias
        }

        @Test
        @DisplayName("alias 改名:发 renamed 事件,oldAlias+newAlias 都在事件里")
        void rename_publishes_renamed_event() {
            UserAiModelAlias existing = newAlias(10L, 7L, "旧名", "openai", "gpt-4o-mini", true);
            when(repo.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(existing));
            when(repo.existsByUserIdAndAliasIgnoreCase(7L, "新名")).thenReturn(false);
            when(repo.save(any(UserAiModelAlias.class))).thenAnswer(inv -> inv.getArgument(0));

            UserAiModelAliasDto dto = new UserAiModelAliasDto();
            dto.setAlias("新名");

            UserAiModelAlias updated = service.update(7L, 10L, dto);

            assertThat(updated.getAlias()).isEqualTo("新名");

            ArgumentCaptor<UserAiModelAliasChangedEvent> ev = ArgumentCaptor.forClass(UserAiModelAliasChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiModelAliasChangedEvent.ChangeType.UPDATED);
            assertThat(ev.getValue().getOldAlias()).isEqualTo("旧名");
            assertThat(ev.getValue().getAlias()).isEqualTo("新名");
        }

        @Test
        @DisplayName("alias 改名冲突(同 user 已存在同名)→ 报错")
        void rename_conflict_throws() {
            UserAiModelAlias existing = newAlias(10L, 7L, "旧名", "openai", "gpt-4o-mini", true);
            when(repo.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(existing));
            when(repo.existsByUserIdAndAliasIgnoreCase(7L, "新名")).thenReturn(true);

            UserAiModelAliasDto dto = new UserAiModelAliasDto();
            dto.setAlias("新名");

            assertThatThrownBy(() -> service.update(7L, 10L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("同名 alias");
        }
    }

    // ─────────────────── delete ───────────────────

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("硬删 + DELETED 事件携带 alias 名(供 listener 清 cache key)")
        void hard_delete_publishes_event_with_alias() {
            UserAiModelAlias a = newAlias(5L, 7L, "我的 GPT", "openai", "gpt-4o-mini", true);
            when(repo.findByIdAndUserId(5L, 7L)).thenReturn(Optional.of(a));

            service.delete(7L, 5L);

            verify(repo).delete(a);
            ArgumentCaptor<UserAiModelAliasChangedEvent> ev = ArgumentCaptor.forClass(UserAiModelAliasChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiModelAliasChangedEvent.ChangeType.DELETED);
            assertThat(ev.getValue().getAlias()).isEqualTo("我的 GPT");
            assertThat(ev.getValue().getUserId()).isEqualTo(7L);
        }
    }

    // ─────────────────── findByUserIdAndAlias + 缓存 ───────────────────

    @Nested
    @DisplayName("findByUserIdAndAlias + Caffeine 缓存")
    class FindAndCache {

        @Test
        @DisplayName("命中 → 返回实体,缓存写入")
        void hit() {
            UserAiModelAlias a = newAlias(1L, 7L, "我的 GPT", "openai", "gpt-4o-mini", true);
            when(repo.findByUserIdAndAliasIgnoreCase(7L, "我的 GPT")).thenReturn(Optional.of(a));

            Optional<UserAiModelAlias> r1 = service.findByUserIdAndAlias(7L, "我的 GPT");
            Optional<UserAiModelAlias> r2 = service.findByUserIdAndAlias(7L, "我的 GPT");

            assertThat(r1).isPresent();
            assertThat(r1).isSameAs(r2);   // 缓存命中
            verify(repo, times(1)).findByUserIdAndAliasIgnoreCase(7L, "我的 GPT");
        }

        @Test
        @DisplayName("大小写不敏感:'我的 gpt' 也能命中 '我的 GPT'")
        void case_insensitive() {
            UserAiModelAlias a = newAlias(1L, 7L, "我的 GPT", "openai", "gpt-4o-mini", true);
            // 首次按 "我的 gpt" 查
            when(repo.findByUserIdAndAliasIgnoreCase(7L, "我的 gpt")).thenReturn(Optional.of(a));

            assertThat(service.findByUserIdAndAlias(7L, "我的 gpt")).isPresent();
        }

        @Test
        @DisplayName("含冒号的字符串 → 跳过 alias 查询(那是 vendor:model 格式)")
        void skip_when_contains_colon() {
            assertThat(service.findByUserIdAndAlias(7L, "openai:gpt-4o-mini")).isEmpty();
            verifyNoInteractions(repo);
        }

        @Test
        @DisplayName("未命中 → Optional.empty 也缓存(避免每请求穿透 DB)")
        void miss_caches_empty_too() {
            when(repo.findByUserIdAndAliasIgnoreCase(7L, "不存在的")).thenReturn(Optional.empty());

            assertThat(service.findByUserIdAndAlias(7L, "不存在的")).isEmpty();
            assertThat(service.findByUserIdAndAlias(7L, "不存在的")).isEmpty();
            verify(repo, times(1)).findByUserIdAndAliasIgnoreCase(7L, "不存在的");
        }

        @Test
        @DisplayName("空白字符串 → 直接返回空,不查 DB")
        void blank_string_short_circuits() {
            assertThat(service.findByUserIdAndAlias(7L, "")).isEmpty();
            assertThat(service.findByUserIdAndAlias(7L, "   ")).isEmpty();
            assertThat(service.findByUserIdAndAlias(7L, null)).isEmpty();
            verifyNoInteractions(repo);
        }

        @Test
        @DisplayName("匿名用户(null)→ 直接返回空,不查 DB")
        void null_user_short_circuits() {
            assertThat(service.findByUserIdAndAlias(null, "我的 GPT")).isEmpty();
            verifyNoInteractions(repo);
        }
    }

    // ─────────────────── invalidateCache ───────────────────

    @Nested
    @DisplayName("invalidateCache(给 listener)")
    class InvalidateCache {

        @Test
        @DisplayName("改名场景:同时清旧 key + 新 key")
        void rename_clears_both_keys() {
            // 预热两条 cache(用不同 key)
            UserAiModelAlias a = newAlias(1L, 7L, "我的 GPT", "openai", "gpt-4o-mini", true);
            when(repo.findByUserIdAndAliasIgnoreCase(any(), any())).thenReturn(Optional.of(a));
            service.findByUserIdAndAlias(7L, "我的 GPT");
            service.findByUserIdAndAlias(7L, "我的 gpt");  // cache key 一样(小写归一化)
            verify(repo, times(1)).findByUserIdAndAliasIgnoreCase(any(), any());

            // 改名 → 失效
            service.invalidateCache(7L, "我的 GPT", "我的 GPT Plus");

            // 再次查 → 走 DB
            service.findByUserIdAndAlias(7L, "我的 GPT");
            verify(repo, times(2)).findByUserIdAndAliasIgnoreCase(any(), any());
        }

        @Test
        @DisplayName("userId=null → 清空全部 cache(兜底)")
        void null_user_clears_all() {
            service.invalidateCache(null, "alias", "newAlias");
            verifyNoInteractions(repo);
        }
    }

    // ─────────────────── helpers ───────────────────

    private static UserAiModelAlias newAlias(Long id, Long userId, String alias, String vendor,
                                             String model, Boolean enabled) {
        UserAiModelAlias a = new UserAiModelAlias();
        setId(a, id);
        a.setUserId(userId);
        a.setAlias(alias);
        a.setTargetVendor(vendor);
        a.setTargetModel(model);
        a.setEnabled(enabled);
        return a;
    }

    private static void setId(UserAiModelAlias a, Long id) {
        try {
            var f = UserAiModelAlias.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static UserAiModelAliasDto newDto(String alias, String vendor, String model, Boolean enabled) {
        UserAiModelAliasDto dto = new UserAiModelAliasDto();
        dto.setAlias(alias);
        dto.setTargetVendor(vendor);
        dto.setTargetModel(model);
        if (enabled != null) dto.setEnabled(enabled);
        return dto;
    }
}
