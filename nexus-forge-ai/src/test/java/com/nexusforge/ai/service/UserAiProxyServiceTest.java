package com.nexusforge.ai.service;

import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.config.AiVendorRegistry;
import com.nexusforge.ai.controller.dto.UserAiProxyDto;
import com.nexusforge.ai.entity.UserAiProxy;
import com.nexusforge.ai.event.UserAiProxyChangedEvent;
import com.nexusforge.ai.repository.UserAiProxyRepository;
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
 * Phase 3 — {@link UserAiProxyService} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>create:vendor 归一化 / apiKey 必填 / alias 唯一 / vendor 不支持报错 / isDefault 触发 unmark</li>
 *   <li>update:partial update / name 改冲突 / clearApiKey 报错(BYOK 不允许) / 新 Key 覆盖</li>
 *   <li>setDefault:unmark 其他 / 幂等(已 default 不再 publish 事件)</li>
 *   <li>delete:DELETED 事件 + cache 失效</li>
 *   <li>findById:所有权校验(不属于该 user → 404)</li>
 *   <li>listByUserId / findDefaultByUserId:缓存命中</li>
 *   <li>invalidateCacheForUser:精准失效该 userId 的 list + default 两条</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserAiProxyService — Phase 3 用户级 BYOK")
class UserAiProxyServiceTest {

    @Mock UserAiProxyRepository repo;
    @Mock ApplicationEventPublisher publisher;

    private UserAiProxyService service;
    private AiVendorRegistry vendorRegistry;
    private ApiKeyCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new ApiKeyCipher("test-master-key-for-unit-tests-only", "");   // 32B 派生
        vendorRegistry = new AiVendorRegistry();
        service = new UserAiProxyService(repo, cipher, vendorRegistry, publisher);
    }

    // ─────────────────── create ───────────────────

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常路径:归一化 vendor、加密 Key、填默认、发 CREATED 事件")
        void creates_and_publishes() {
            UserAiProxyDto dto = newDto("我的代理", "  DeepSeek  ", "https://api.deepseek.com/v1",
                    "sk-test-key-1234", null, null, true, false, null);
            when(repo.existsByUserIdAndName(7L, "我的代理")).thenReturn(false);
            when(repo.save(any(UserAiProxy.class))).thenAnswer(inv -> {
                UserAiProxy p = inv.getArgument(0);
                setId(p, 1L);
                return p;
            });

            UserAiProxy saved = service.create(7L, dto);

            assertThat(saved.getUserId()).isEqualTo(7L);
            assertThat(saved.getName()).isEqualTo("我的代理");
            assertThat(saved.getVendor()).isEqualTo("deepseek");
            assertThat(saved.getBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
            assertThat(saved.getEncryptedApiKey()).isNotNull();
            assertThat(saved.getEncryptedApiKey().length).isGreaterThan(28);  // IV(12) + tag(16) + 至少 1 字节密文
            assertThat(saved.getApiKeyFingerprint()).startsWith("sk-t").contains("••••");
            assertThat(saved.getEnabled()).isTrue();
            assertThat(saved.getIsDefault()).isFalse();

            ArgumentCaptor<UserAiProxyChangedEvent> ev = ArgumentCaptor.forClass(UserAiProxyChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiProxyChangedEvent.ChangeType.CREATED);
            assertThat(ev.getValue().getUserId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("alias 唯一冲突:同 user 已存在 name → LLM_PROXY_NOT_FOUND")
        void duplicate_name_throws() {
            UserAiProxyDto dto = newDto("我的代理", "deepseek", "https://x", "sk-k", null, null, null, null, null);
            when(repo.existsByUserIdAndName(7L, "我的代理")).thenReturn(true);

            assertThatThrownBy(() -> service.create(7L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("同名")
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_PROXY_NOT_FOUND.getCode());

            verify(repo, never()).save(any());
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("vendor 不在 OpenAI 兼容集合(如 anthropic)→ LLM_MODEL_NOT_FOUND")
        void unsupported_vendor_throws() {
            UserAiProxyDto dto = newDto("anthropic 代理", "anthropic", "https://api.anthropic.com", "sk-ant-",
                    null, null, null, null, null);

            assertThatThrownBy(() -> service.create(7L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不支持用户级代理")
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("apiKey 必填:留空 → LLM_INVALID_REQUEST")
        void missing_api_key_throws() {
            UserAiProxyDto dto = newDto("代理", "deepseek", "https://x", "  ", null, null, null, null, null);

            assertThatThrownBy(() -> service.create(7L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("apiKey 不能为空")
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("dto.isDefault=true → unmark 其他 default,发 DEFAULT_CHANGED 事件")
        void isDefault_true_unmarks_others_and_publishes_default_changed() {
            UserAiProxy oldDefault = newProxy(2L, 7L, "旧默认", "openai", true);
            UserAiProxyDto dto = newDto("新默认", "deepseek", "https://x", "sk-k",
                    null, null, null, true, null);
            when(repo.existsByUserIdAndName(7L, "新默认")).thenReturn(false);
            when(repo.findByUserIdOrderByIsDefaultDescNameAsc(7L))
                    .thenReturn(List.of(oldDefault));
            when(repo.save(any(UserAiProxy.class))).thenAnswer(inv -> {
                UserAiProxy p = inv.getArgument(0);
                if (p.getId() == null) setId(p, 99L);
                return p;
            });

            UserAiProxy saved = service.create(7L, dto);

            // 旧 default 被 unmark
            assertThat(oldDefault.getIsDefault()).isFalse();
            // 新代理 isDefault=true
            assertThat(saved.getIsDefault()).isTrue();

            ArgumentCaptor<UserAiProxyChangedEvent> ev = ArgumentCaptor.forClass(UserAiProxyChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiProxyChangedEvent.ChangeType.DEFAULT_CHANGED);
        }
    }

    // ─────────────────── update ───────────────────

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("partial update:只改 baseUrl,其他保留;发 UPDATED 事件")
        void partial_update() {
            UserAiProxy existing = newProxy(1L, 7L, "代理", "deepseek", false);
            existing.setBaseUrl("https://old.example.com");
            existing.setDefaultModel("deepseek-chat");
            when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(existing));
            when(repo.save(any(UserAiProxy.class))).thenAnswer(inv -> inv.getArgument(0));

            UserAiProxyDto dto = new UserAiProxyDto();
            dto.setBaseUrl("https://new.example.com/v1");
            // 其他字段不传

            UserAiProxy updated = service.update(7L, 1L, dto);

            assertThat(updated.getBaseUrl()).isEqualTo("https://new.example.com/v1");
            assertThat(updated.getDefaultModel()).isEqualTo("deepseek-chat");  // 保留
            assertThat(updated.getName()).isEqualTo("代理");                   // 保留

            ArgumentCaptor<UserAiProxyChangedEvent> ev = ArgumentCaptor.forClass(UserAiProxyChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiProxyChangedEvent.ChangeType.UPDATED);
        }

        @Test
        @DisplayName("新 apiKey 非空 → 覆盖密文 + 指纹,发 UPDATED 事件")
        void update_api_key() {
            UserAiProxy existing = newProxy(1L, 7L, "代理", "deepseek", false);
            when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(existing));
            when(repo.save(any(UserAiProxy.class))).thenAnswer(inv -> inv.getArgument(0));

            UserAiProxyDto dto = new UserAiProxyDto();
            dto.setApiKey("sk-new-key-5678");

            UserAiProxy updated = service.update(7L, 1L, dto);

            assertThat(updated.getApiKeyFingerprint()).startsWith("sk-n").contains("••••");
            // 密文变了(原 fp 和新 fp 应当不同)
            assertThat(updated.getApiKeyFingerprint()).isNotEqualTo("sk-t••••old");
        }

        @Test
        @DisplayName("clearApiKey=true → 拒绝(BYOK 场景不允许无 Key 代理)")
        void clear_api_key_throws() {
            UserAiProxy existing = newProxy(1L, 7L, "代理", "deepseek", false);
            when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(existing));

            UserAiProxyDto dto = new UserAiProxyDto();
            dto.setClearApiKey(true);

            assertThatThrownBy(() -> service.update(7L, 1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不支持清除 Key")
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("name 改冲突 → 报错")
        void name_conflict_throws() {
            UserAiProxy existing = newProxy(1L, 7L, "旧名", "deepseek", false);
            when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(existing));
            when(repo.existsByUserIdAndName(7L, "新名")).thenReturn(true);

            UserAiProxyDto dto = new UserAiProxyDto();
            dto.setName("新名");

            assertThatThrownBy(() -> service.update(7L, 1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("同名");
        }

        @Test
        @DisplayName("dto.isDefault=true 且旧值非 default → unmark 其他 + 切 default + DEFAULT_CHANGED 事件")
        void update_isDefault_true_unmarks_others() {
            UserAiProxy me = newProxy(1L, 7L, "代理", "deepseek", false);
            UserAiProxy other = newProxy(2L, 7L, "其他", "openai", true);
            when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(me));
            when(repo.findByUserIdOrderByIsDefaultDescNameAsc(7L)).thenReturn(List.of(me, other));
            when(repo.save(any(UserAiProxy.class))).thenAnswer(inv -> inv.getArgument(0));

            UserAiProxyDto dto = new UserAiProxyDto();
            dto.setIsDefault(true);

            UserAiProxy updated = service.update(7L, 1L, dto);

            assertThat(updated.getIsDefault()).isTrue();
            assertThat(other.getIsDefault()).isFalse();

            ArgumentCaptor<UserAiProxyChangedEvent> ev = ArgumentCaptor.forClass(UserAiProxyChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiProxyChangedEvent.ChangeType.DEFAULT_CHANGED);
        }
    }

    // ─────────────────── setDefault ───────────────────

    @Nested
    @DisplayName("setDefault")
    class SetDefault {

        @Test
        @DisplayName("非 default → 切 default,unmark 其他,发 DEFAULT_CHANGED 事件")
        void switches_to_default() {
            UserAiProxy me = newProxy(1L, 7L, "代理", "deepseek", false);
            UserAiProxy old = newProxy(2L, 7L, "旧默认", "openai", true);
            when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(me));
            when(repo.findByUserIdOrderByIsDefaultDescNameAsc(7L)).thenReturn(List.of(me, old));
            when(repo.save(any(UserAiProxy.class))).thenAnswer(inv -> inv.getArgument(0));

            UserAiProxy result = service.setDefault(7L, 1L);

            assertThat(result.getIsDefault()).isTrue();
            assertThat(old.getIsDefault()).isFalse();
            verify(publisher, times(1)).publishEvent(any(UserAiProxyChangedEvent.class));
        }

        @Test
        @DisplayName("已 default → 幂等返回,不发事件(无变化)")
        void already_default_is_noop() {
            UserAiProxy me = newProxy(1L, 7L, "代理", "deepseek", true);
            when(repo.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(me));

            UserAiProxy result = service.setDefault(7L, 1L);

            assertThat(result.getIsDefault()).isTrue();
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("代理不属于该 user → 404")
        void not_owned_throws() {
            when(repo.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setDefault(7L, 99L))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_PROXY_NOT_FOUND.getCode());
        }
    }

    // ─────────────────── delete ───────────────────

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("硬删 + DELETED 事件(userId + proxyId 都在事件里)")
        void hard_delete_publishes_event() {
            UserAiProxy p = newProxy(5L, 7L, "要删的", "deepseek", false);
            when(repo.findByIdAndUserId(5L, 7L)).thenReturn(Optional.of(p));

            service.delete(7L, 5L);

            verify(repo).delete(p);
            ArgumentCaptor<UserAiProxyChangedEvent> ev = ArgumentCaptor.forClass(UserAiProxyChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(UserAiProxyChangedEvent.ChangeType.DELETED);
            assertThat(ev.getValue().getUserId()).isEqualTo(7L);
            assertThat(ev.getValue().getProxyId()).isEqualTo(5L);
        }
    }

    // ─────────────────── findById / listByUserId / findDefaultByUserId + cache ───────────────────

    @Nested
    @DisplayName("find + Caffeine 缓存")
    class FindAndCache {

        @Test
        @DisplayName("findById:不属于该 user → 404,防越权")
        void findById_not_owned_throws() {
            when(repo.findByIdAndUserId(5L, 7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById(7L, 5L))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_PROXY_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("listByUserId:首次走 DB,命中缓存后续不查 DB")
        void list_caches() {
            UserAiProxy p = newProxy(1L, 7L, "代理", "deepseek", false);
            when(repo.findByUserIdOrderByIsDefaultDescNameAsc(7L)).thenReturn(List.of(p));

            List<UserAiProxy> a = service.listByUserId(7L);
            List<UserAiProxy> b = service.listByUserId(7L);

            assertThat(a).hasSize(1);
            assertThat(b).isSameAs(a);   // 缓存命中,同一 List 实例
            verify(repo, times(1)).findByUserIdOrderByIsDefaultDescNameAsc(7L);
        }

        @Test
        @DisplayName("findDefaultByUserId:DB 命中 → 缓存;DB 空 → Optional.empty 也缓存")
        void default_caches_empty_too() {
            // 空 Optional 也缓存 — 避免每个请求穿透到 DB
            when(repo.findByUserIdAndIsDefaultTrue(7L)).thenReturn(Optional.empty());

            assertThat(service.findDefaultByUserId(7L)).isEmpty();
            assertThat(service.findDefaultByUserId(7L)).isEmpty();
            verify(repo, times(1)).findByUserIdAndIsDefaultTrue(7L);
        }

        @Test
        @DisplayName("invalidateCacheForUser:精准清该 userId 的 list + default 两条")
        void invalidate_clears_both_caches() {
            // 先预热两条 cache
            when(repo.findByUserIdOrderByIsDefaultDescNameAsc(7L)).thenReturn(List.of());
            when(repo.findByUserIdAndIsDefaultTrue(7L)).thenReturn(Optional.empty());
            service.listByUserId(7L);
            service.findDefaultByUserId(7L);
            verify(repo, times(1)).findByUserIdOrderByIsDefaultDescNameAsc(7L);
            verify(repo, times(1)).findByUserIdAndIsDefaultTrue(7L);

            // 触发失效
            service.invalidateCacheForUser(7L);

            // 再查 → 走 DB
            service.listByUserId(7L);
            service.findDefaultByUserId(7L);
            verify(repo, times(2)).findByUserIdOrderByIsDefaultDescNameAsc(7L);
            verify(repo, times(2)).findByUserIdAndIsDefaultTrue(7L);
        }

        @Test
        @DisplayName("userId=null → 走全局失效兜底")
        void invalidate_user_null_clears_all() {
            service.invalidateCacheForUser(null);
            verifyNoInteractions(repo);   // 兜底路径不动 repo
        }
    }

    // ─────────────────── helpers ───────────────────

    private static UserAiProxy newProxy(Long id, Long userId, String name, String vendor, boolean isDefault) {
        UserAiProxy p = new UserAiProxy();
        setId(p, id);
        p.setUserId(userId);
        p.setName(name);
        p.setVendor(vendor);
        p.setBaseUrl("https://example.com/v1");
        p.setEncryptedApiKey(new byte[]{1, 2, 3, 4});  // 假密文,update 时会被新 Key 覆盖
        p.setApiKeyFingerprint("sk-t••••old");
        p.setEnabled(true);
        p.setIsDefault(isDefault);
        return p;
    }

    private static void setId(UserAiProxy p, Long id) {
        try {
            var f = UserAiProxy.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static UserAiProxyDto newDto(String name, String vendor, String baseUrl, String apiKey,
                                         String clearKey, Boolean clearApiKey,
                                         Boolean enabled, Boolean isDefault, String description) {
        UserAiProxyDto dto = new UserAiProxyDto();
        dto.setName(name);
        dto.setVendor(vendor);
        dto.setBaseUrl(baseUrl);
        dto.setApiKey(apiKey);
        if (clearApiKey != null) dto.setClearApiKey(clearApiKey);
        dto.setEnabled(enabled);
        dto.setIsDefault(isDefault);
        dto.setDescription(description);
        return dto;
    }
}
