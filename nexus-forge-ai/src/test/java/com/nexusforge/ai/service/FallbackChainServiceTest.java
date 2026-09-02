package com.nexusforge.ai.service;

import com.nexusforge.ai.entity.AiFallbackChain;
import com.nexusforge.ai.event.FallbackChainChangedEvent;
import com.nexusforge.ai.repository.AiFallbackChainRepository;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainSource;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainView;
import com.nexusforge.config.AiProperties;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 7 — {@link FallbackChainService} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>findEffective:DB → yaml → empty 三段语义,DB 路径走 Caffeine 缓存</li>
 *   <li>replace:upsert + 发 REPLACED 事件;校验 + 去重 + 大小写归一化 + 拒绝空项 / 未知 vendor</li>
 *   <li>reset:物理 deleteById + 发 RESET 事件;幂等(DB 无行时不发事件)</li>
 *   <li>cache 失效:replace 后再 findEffective 应反映新值</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FallbackChainService — Phase 7 降级链 DB 化")
class FallbackChainServiceTest {

    @Mock AiFallbackChainRepository repo;
    @Mock ApplicationEventPublisher publisher;

    private FallbackChainService service;
    private AiProperties yamlProps;

    @BeforeEach
    void setUp() {
        yamlProps = new AiProperties();
        Map<String, AiProperties.Provider> providers = new HashMap<>();
        for (String v : List.of("openai", "anthropic", "ollama", "deepseek")) {
            AiProperties.Provider p = new AiProperties.Provider();
            p.setEnabled(true);
            p.setDefaultModel("default-" + v);
            providers.put(v, p);
        }
        yamlProps.setProviders(providers);
        // 默认 yaml 无 fallback chain — 多数 case 用 DB 路径
        yamlProps.setFallbackChain(new ArrayList<>());

        service = new FallbackChainService(repo, yamlProps, publisher);
    }

    private AiFallbackChain dbRow(List<String> vendors) {
        AiFallbackChain row = new AiFallbackChain();
        row.setId(1);
        row.setVendors(new ArrayList<>(vendors));
        row.setUpdatedAt(OffsetDateTime.now());
        return row;
    }

    // ─────────────────────── findEffective ───────────────────────

    @Nested
    @DisplayName("findEffective — DB / yaml / empty 三段语义")
    class FindEffective {

        @Test
        @DisplayName("DB 有行 + vendors 非空 → source=DB,vendors 跟 DB 一致")
        void db_present_nonempty() {
            when(repo.findById(1)).thenReturn(Optional.of(dbRow(List.of("ollama", "deepseek"))));

            FallbackChainView v = service.findEffective();

            assertThat(v.source()).isEqualTo(FallbackChainSource.DB);
            assertThat(v.vendors()).containsExactly("ollama", "deepseek");
            assertThat(v.updatedAt()).isNotNull();
        }

        @Test
        @DisplayName("DB 有行 + vendors 空 list → source=DB,vendors=[] (显式空降级,跟 yaml 兜底区分)")
        void db_present_empty_list() {
            when(repo.findById(1)).thenReturn(Optional.of(dbRow(List.of())));

            FallbackChainView v = service.findEffective();

            assertThat(v.source()).isEqualTo(FallbackChainSource.DB);
            assertThat(v.vendors()).isEmpty();
        }

        @Test
        @DisplayName("DB 无行 + yaml 有 fallback-chain → source=YAML_FALLBACK,vendors=yaml")
        void db_absent_yaml_present() {
            when(repo.findById(1)).thenReturn(Optional.empty());
            yamlProps.setFallbackChain(new ArrayList<>(List.of("ollama", "anthropic")));

            FallbackChainView v = service.findEffective();

            assertThat(v.source()).isEqualTo(FallbackChainSource.YAML_FALLBACK);
            assertThat(v.vendors()).containsExactly("ollama", "anthropic");
            assertThat(v.updatedAt()).isNull();
        }

        @Test
        @DisplayName("DB 无行 + yaml 空 → source=EMPTY,vendors=[] (无降级)")
        void db_absent_yaml_empty() {
            when(repo.findById(1)).thenReturn(Optional.empty());

            FallbackChainView v = service.findEffective();

            assertThat(v.source()).isEqualTo(FallbackChainSource.EMPTY);
            assertThat(v.vendors()).isEmpty();
        }

        @Test
        @DisplayName("DB 无行 + yaml 是 null(没配)→ source=EMPTY,优雅处理不 NPE")
        void db_absent_yaml_null() {
            when(repo.findById(1)).thenReturn(Optional.empty());
            yamlProps.setFallbackChain(null);

            FallbackChainView v = service.findEffective();

            assertThat(v.source()).isEqualTo(FallbackChainSource.EMPTY);
            assertThat(v.vendors()).isEmpty();
        }

        @Test
        @DisplayName("DB 路径有 Caffeine 缓存(同 5min 内多次调 → 只查 DB 一次)")
        void db_path_caches() {
            when(repo.findById(1)).thenReturn(Optional.of(dbRow(List.of("ollama"))));

            service.findEffective();
            service.findEffective();
            service.findEffective();

            // DB 路径走 cache,3 次调用只查 1 次 DB
            verify(repo, times(1)).findById(1);
        }

        @Test
        @DisplayName("YAML 兜底路径不缓存(避免 yaml 改了不生效;此 case 是 5min TTL 异常路径兜底)")
        void yaml_fallback_does_not_cache() {
            when(repo.findById(1)).thenReturn(Optional.empty());
            yamlProps.setFallbackChain(new ArrayList<>(List.of("ollama")));

            service.findEffective();
            service.findEffective();

            // YAML 路径不缓存,每次都查 DB(虽然 DB 是空,但 cache 路径不会拦截)
            verify(repo, times(2)).findById(1);
        }
    }

    // ─────────────────────── replace ───────────────────────

    @Nested
    @DisplayName("replace — 全量替换 + 校验 + 事件")
    class Replace {

        @Test
        @DisplayName("happy:非空 list → upsert + 发 REPLACED 事件")
        void happy_nonempty() {
            when(repo.findById(1)).thenReturn(Optional.empty());
            when(repo.save(any(AiFallbackChain.class))).thenAnswer(inv -> {
                AiFallbackChain r = inv.getArgument(0);
                r.setUpdatedAt(OffsetDateTime.now());
                return r;
            });

            FallbackChainView v = service.replace(List.of("ollama", "deepseek"));

            assertThat(v.source()).isEqualTo(FallbackChainSource.DB);
            assertThat(v.vendors()).containsExactly("ollama", "deepseek");
            assertThat(v.updatedAt()).isNotNull();

            // 事件
            ArgumentCaptor<FallbackChainChangedEvent> evCap = ArgumentCaptor.forClass(FallbackChainChangedEvent.class);
            verify(publisher).publishEvent(evCap.capture());
            assertThat(evCap.getValue().getChangeType()).isEqualTo(FallbackChainChangedEvent.ChangeType.REPLACED);
            assertThat(evCap.getValue().getVendors()).containsExactly("ollama", "deepseek");
        }

        @Test
        @DisplayName("空 list 合法:显式禁用降级,DB 仍写入空 vendors")
        void empty_list_legal() {
            when(repo.findById(1)).thenReturn(Optional.empty());
            when(repo.save(any(AiFallbackChain.class))).thenAnswer(inv -> {
                AiFallbackChain r = inv.getArgument(0);
                r.setUpdatedAt(OffsetDateTime.now());
                return r;
            });

            FallbackChainView v = service.replace(List.of());

            assertThat(v.source()).isEqualTo(FallbackChainSource.DB);
            assertThat(v.vendors()).isEmpty();
        }

        @Test
        @DisplayName("vendors 是 null → 抛 LLM_INVALID_REQUEST(空 list 跟 null 是不同语义)")
        void null_vendors_rejected() {
            assertThatThrownBy(() -> service.replace(null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("含 null 项 → 抛 LLM_INVALID_REQUEST")
        void null_entry_rejected() {
            assertThatThrownBy(() -> service.replace(Arrays.asList("openai", null, "ollama")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("含空串 / 纯空白项 → 抛 LLM_INVALID_REQUEST")
        void blank_entry_rejected() {
            assertThatThrownBy(() -> service.replace(List.of("openai", "  ", "ollama")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("含不存在于 yaml 的 vendor → 抛 LLM_INVALID_REQUEST")
        void unknown_vendor_rejected() {
            assertThatThrownBy(() -> service.replace(List.of("openai", "ghost-vendor")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("重复项去重(LinkedHashSet 保序)")
        void dedup_preserves_order() {
            when(repo.findById(1)).thenReturn(Optional.empty());
            when(repo.save(any(AiFallbackChain.class))).thenAnswer(inv -> {
                AiFallbackChain r = inv.getArgument(0);
                r.setUpdatedAt(OffsetDateTime.now());
                return r;
            });

            FallbackChainView v = service.replace(List.of("ollama", "ollama", "openai", "ollama"));

            assertThat(v.vendors()).containsExactly("ollama", "openai");
        }

        @Test
        @DisplayName("大小写归一化(OpenAI → openai)")
        void case_normalized() {
            when(repo.findById(1)).thenReturn(Optional.empty());
            when(repo.save(any(AiFallbackChain.class))).thenAnswer(inv -> {
                AiFallbackChain r = inv.getArgument(0);
                r.setUpdatedAt(OffsetDateTime.now());
                return r;
            });

            FallbackChainView v = service.replace(List.of("OpenAI", "OLLAMA"));

            assertThat(v.vendors()).containsExactly("openai", "ollama");
        }

        @Test
        @DisplayName("replace 写完 + 发事件后 cache 失效;下次 findEffective 看到新链")
        void replace_invalidates_cache() {
            // 1. 旧 DB 状态:有 ollama
            when(repo.findById(1))
                    .thenReturn(Optional.of(dbRow(List.of("ollama"))))     // 第一次查(findEffective)
                    .thenReturn(Optional.of(dbRow(List.of("ollama"))))     // 第二次查(replace 内部读)
                    .thenReturn(Optional.of(dbRow(List.of("ollama"))));    // 第三次查(replace 后 findEffective 走 DB)
            when(repo.save(any(AiFallbackChain.class))).thenAnswer(inv -> {
                AiFallbackChain r = inv.getArgument(0);
                r.setUpdatedAt(OffsetDateTime.now());
                return r;
            });

            // 触发 cache 命中
            FallbackChainView before = service.findEffective();
            assertThat(before.vendors()).containsExactly("ollama");

            // replace
            service.replace(List.of("deepseek"));

            // 关键:replace 后,findEffective 再次查询要走到 DB(因为 cache 被失效);
            // 这里 stub findById 仍返 "ollama"(因为 after save 我们的 stub 没刷新),
            // 但真实场景下 DB 已经更新 — 测的是 cache 失效行为本身
            FallbackChainView after = service.findEffective();
            // findEffective(1) + replace 内部读(1) + replace 后 findEffective 走 DB(1) = 3 次
            // 老 cache 还在的话,replace 后不会查第 3 次;看到 3 次说明 cache 被失效了
            verify(repo, atLeast(3)).findById(1);
        }
    }

    // ─────────────────────── reset ───────────────────────

    @Nested
    @DisplayName("reset — 物理删行 + 发 RESET 事件(幂等)")
    class Reset {

        @Test
        @DisplayName("happy:DB 有行 → deleteById + 发 RESET 事件 + 返回 yaml 兜底视图")
        void happy_with_row() {
            when(repo.existsById(1)).thenReturn(true);
            when(repo.findById(1)).thenReturn(Optional.empty());   // delete 后再查返空
            yamlProps.setFallbackChain(new ArrayList<>(List.of("ollama", "anthropic")));

            FallbackChainView v = service.reset();

            verify(repo).deleteById(1);
            assertThat(v.source()).isEqualTo(FallbackChainSource.YAML_FALLBACK);
            assertThat(v.vendors()).containsExactly("ollama", "anthropic");

            ArgumentCaptor<FallbackChainChangedEvent> evCap = ArgumentCaptor.forClass(FallbackChainChangedEvent.class);
            verify(publisher).publishEvent(evCap.capture());
            assertThat(evCap.getValue().getChangeType()).isEqualTo(FallbackChainChangedEvent.ChangeType.RESET);
        }

        @Test
        @DisplayName("幂等:DB 无行 → 不调 deleteById + 不发事件 + 返 yaml 兜底")
        void idempotent_no_row() {
            when(repo.existsById(1)).thenReturn(false);
            yamlProps.setFallbackChain(new ArrayList<>(List.of("ollama")));

            FallbackChainView v = service.reset();

            verify(repo, never()).deleteById(1);
            verify(publisher, never()).publishEvent(any());
            assertThat(v.source()).isEqualTo(FallbackChainSource.YAML_FALLBACK);
            assertThat(v.vendors()).containsExactly("ollama");
        }

        @Test
        @DisplayName("DB 无行 + yaml 空 → reset 返 EMPTY")
        void reset_yields_empty_when_yaml_empty() {
            when(repo.existsById(1)).thenReturn(false);

            FallbackChainView v = service.reset();

            assertThat(v.source()).isEqualTo(FallbackChainSource.EMPTY);
            assertThat(v.vendors()).isEmpty();
        }
    }

    // ─────────────────────── invalidate ───────────────────────

    @Nested
    @DisplayName("invalidate — 公开给 listener 调")
    class Invalidate {

        @Test
        @DisplayName("invalidate 不会抛错(单元测试不依赖 cache 状态)")
        void invalidate_does_not_throw() {
            // 简单 smoke test — invalidate 自身是 cache 失效,内部 Caffeine 行为不在单元测试覆盖
            service.invalidate();
            // 后续 findEffective 仍能用
            when(repo.findById(1)).thenReturn(Optional.empty());
            assertThat(service.findEffective().source()).isEqualTo(FallbackChainSource.EMPTY);
        }
    }
}
