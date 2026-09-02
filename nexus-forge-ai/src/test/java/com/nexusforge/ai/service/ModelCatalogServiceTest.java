package com.nexusforge.ai.service;

import com.nexusforge.ai.controller.dto.ModelCatalogDto;
import com.nexusforge.ai.entity.AiModelCatalog;
import com.nexusforge.ai.event.ModelCatalogChangedEvent;
import com.nexusforge.ai.repository.AiModelCatalogRepository;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 1 — {@link ModelCatalogService} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>create:unique 冲突 / vendor 归一化 / 默认值填充 / 事件发布</li>
 *   <li>update:partial update(null 字段保留旧值)/ 事件发布</li>
 *   <li>setEnabled:单独切 enabled / ENABLED_TOGGLED 事件</li>
 *   <li>delete:硬删 / DELETED 事件带 vendor+modelName</li>
 *   <li>findByVendorModel:缓存命中 / 缓存未命中查 DB / 缓存写入</li>
 *   <li>seedFromYamlIfEmpty:catalog 空才 seed / 已存在跳过 / yamlDefaults 复制</li>
 *   <li>缓存失效:create 后再查同样的 (vendor, model) 应该走 DB 拿到新数据</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ModelCatalogService — Phase 1 多模型管理")
class ModelCatalogServiceTest {

    @Mock AiModelCatalogRepository repo;
    @Mock ApplicationEventPublisher publisher;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(repo, publisher);
    }

    // ─────────────────── create ───────────────────

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常路径:归一化 vendor、填默认、save、发 CREATED 事件")
        void creates_and_publishes() {
            ModelCatalogDto dto = new ModelCatalogDto();
            dto.setVendor("  OpenAI  ");   // 大写 + 空格 → 归一化 openai
            dto.setModelName("gpt-4o-mini");
            dto.setDisplayName("GPT-4o mini");
            // 不传 enabled / supportsVision 等 → 走实体 default
            when(repo.existsByVendorAndModelName("openai", "gpt-4o-mini")).thenReturn(false);
            when(repo.save(any(AiModelCatalog.class))).thenAnswer(inv -> {
                AiModelCatalog m = inv.getArgument(0);
                // 模拟 DB 回填 id
                try {
                    var f = AiModelCatalog.class.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(m, 1L);
                } catch (Exception e) { /* test reflection only */ }
                return m;
            });

            AiModelCatalog saved = service.create(dto);

            assertThat(saved.getVendor()).isEqualTo("openai");
            assertThat(saved.getModelName()).isEqualTo("gpt-4o-mini");
            assertThat(saved.getEnabled()).isTrue();
            assertThat(saved.getSupportsTools()).isTrue();
            assertThat(saved.getSupportsVision()).isFalse();
            assertThat(saved.getTier()).isEqualTo("STANDARD");

            ArgumentCaptor<ModelCatalogChangedEvent> ev = ArgumentCaptor.forClass(ModelCatalogChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(ModelCatalogChangedEvent.ChangeType.CREATED);
            assertThat(ev.getValue().getVendor()).isEqualTo("openai");
            assertThat(ev.getValue().getModelName()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("unique 冲突:vendor + model 已存在 → LLM_MODEL_NOT_FOUND")
        void duplicate_throws() {
            ModelCatalogDto dto = new ModelCatalogDto();
            dto.setVendor("openai");
            dto.setModelName("gpt-4o-mini");
            when(repo.existsByVendorAndModelName("openai", "gpt-4o-mini")).thenReturn(true);

            assertThatThrownBy(() -> service.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已存在")
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());

            verify(repo, never()).save(any());
            verify(publisher, never()).publishEvent(any());
        }
    }

    // ─────────────────── update ───────────────────

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("partial update:只改 displayName,其他保留旧值,发 UPDATED 事件")
        void partial_update_preserves_nulls() {
            AiModelCatalog existing = newModel(10L, "openai", "gpt-4o-mini", true);
            existing.setDisplayName("Old Name");
            existing.setSupportsTools(true);
            when(repo.findById(10L)).thenReturn(Optional.of(existing));
            when(repo.save(any(AiModelCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

            ModelCatalogDto dto = new ModelCatalogDto();
            dto.setDisplayName("New Name");   // 只改这一个
            // enabled / supportsTools / cost 等不传 → 保留旧值

            AiModelCatalog updated = service.update(10L, dto);

            assertThat(updated.getDisplayName()).isEqualTo("New Name");
            assertThat(updated.getEnabled()).isTrue();                  // 保留
            assertThat(updated.getSupportsTools()).isTrue();             // 保留
            assertThat(updated.getModelName()).isEqualTo("gpt-4o-mini"); // 保留

            ArgumentCaptor<ModelCatalogChangedEvent> ev = ArgumentCaptor.forClass(ModelCatalogChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(ModelCatalogChangedEvent.ChangeType.UPDATED);
        }
    }

    // ─────────────────── setEnabled ───────────────────

    @Nested
    @DisplayName("setEnabled")
    class SetEnabled {

        @Test
        @DisplayName("切 enabled:发 ENABLED_TOGGLED 事件(独立 type 便于审计)")
        void toggles_and_publishes_distinct_event_type() {
            AiModelCatalog m = newModel(5L, "openai", "gpt-4o", true);
            when(repo.findById(5L)).thenReturn(Optional.of(m));
            when(repo.save(any(AiModelCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

            AiModelCatalog result = service.setEnabled(5L, false);

            assertThat(result.getEnabled()).isFalse();

            ArgumentCaptor<ModelCatalogChangedEvent> ev = ArgumentCaptor.forClass(ModelCatalogChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(ModelCatalogChangedEvent.ChangeType.ENABLED_TOGGLED);
        }
    }

    // ─────────────────── delete ───────────────────

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("硬删:DELETED 事件携带 vendor + modelName 供 listener 精准失效")
        void hard_delete_publishes_with_vendor_model() {
            AiModelCatalog m = newModel(7L, "deepseek", "deepseek-v4-flash", true);
            when(repo.findById(7L)).thenReturn(Optional.of(m));

            service.delete(7L);

            verify(repo).delete(m);

            ArgumentCaptor<ModelCatalogChangedEvent> ev = ArgumentCaptor.forClass(ModelCatalogChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(ModelCatalogChangedEvent.ChangeType.DELETED);
            assertThat(ev.getValue().getVendor()).isEqualTo("deepseek");
            assertThat(ev.getValue().getModelName()).isEqualTo("deepseek-v4-flash");
        }
    }

    // ─────────────────── findByVendorModel + 缓存 ───────────────────

    @Nested
    @DisplayName("findByVendorModel + Caffeine 缓存")
    class FindByVendorModel {

        @Test
        @DisplayName("首次查询走 DB,结果回填缓存")
        void first_call_queries_db_and_populates_cache() {
            AiModelCatalog m = newModel(1L, "openai", "gpt-4o-mini", true);
            when(repo.findByVendorAndModelName("openai", "gpt-4o-mini"))
                    .thenReturn(Optional.of(m));

            AiModelCatalog r1 = service.findByVendorModel("openai", "gpt-4o-mini");
            AiModelCatalog r2 = service.findByVendorModel("openai", "gpt-4o-mini");

            assertThat(r1).isSameAs(r2);
            // DB 只查了一次(第二次命中缓存)
            verify(repo, times(1)).findByVendorAndModelName(any(), any());
        }

        @Test
        @DisplayName("DB 不存在 → 返回 null,不写缓存")
        void not_found_returns_null_no_cache_pollution() {
            when(repo.findByVendorAndModelName("openai", "unknown"))
                    .thenReturn(Optional.empty());

            assertThat(service.findByVendorModel("openai", "unknown")).isNull();
            // 再次查询:仍然查 DB(没污染缓存)
            assertThat(service.findByVendorModel("openai", "unknown")).isNull();
            verify(repo, times(2)).findByVendorAndModelName(any(), any());
        }

        @Test
        @DisplayName("vendor / model null → 跳过查 DB,直接返回 null")
        void null_input_returns_null_no_db_call() {
            assertThat(service.findByVendorModel(null, "gpt-4o-mini")).isNull();
            assertThat(service.findByVendorModel("openai", null)).isNull();
            assertThat(service.findByVendorModel(null, null)).isNull();
            verifyNoInteractions(repo);
        }

        @Test
        @DisplayName("缓存失效后:再次查询走 DB 拿新数据")
        void cache_invalidation_refreshes_data() {
            AiModelCatalog original = newModel(1L, "openai", "gpt-4o", true);
            when(repo.findByVendorAndModelName("openai", "gpt-4o"))
                    .thenReturn(Optional.of(original));

            assertThat(service.findByVendorModel("openai", "gpt-4o").getEnabled()).isTrue();

            // 模拟 admin 改了 model:DB 现在返回 disabled 版本
            AiModelCatalog updated = newModel(1L, "openai", "gpt-4o", false);
            when(repo.findByVendorAndModelName("openai", "gpt-4o"))
                    .thenReturn(Optional.of(updated));

            // 缓存命中,仍然返回 true(旧值)
            assertThat(service.findByVendorModel("openai", "gpt-4o").getEnabled()).isTrue();

            // listener 触发失效(实际由事件触发,这里直接调 service.invalidateCache)
            service.invalidateCache("openai", "gpt-4o");

            // 再次查询:走 DB,拿到新值
            assertThat(service.findByVendorModel("openai", "gpt-4o").getEnabled()).isFalse();
        }
    }

    // ─────────────────── seed ───────────────────

    @Nested
    @DisplayName("seedFromYamlIfEmpty")
    class SeedFromYaml {

        @Test
        @DisplayName("catalog 非空 → 跳过 seed(不覆盖已有数据)")
        void skips_when_catalog_not_empty() {
            when(repo.count()).thenReturn(3L);

            int created = service.seedFromYamlIfEmpty(Map.of("openai", "gpt-4o-mini"));

            assertThat(created).isZero();
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("catalog 空 + yaml 有 defaults → 全部插入,跳过已存在")
        void inserts_all_from_yaml_when_empty() {
            when(repo.count()).thenReturn(0L);
            when(repo.existsByVendorAndModelName("openai", "gpt-4o-mini")).thenReturn(false);
            when(repo.existsByVendorAndModelName("deepseek", "deepseek-v4-flash")).thenReturn(true);   // 已存在,跳过
            when(repo.existsByVendorAndModelName("ollama", "llama3.1")).thenReturn(false);
            when(repo.save(any(AiModelCatalog.class))).thenAnswer(inv -> inv.getArgument(0));

            Map<String, String> yaml = new LinkedHashMap<>();
            yaml.put("openai", "gpt-4o-mini");
            yaml.put("deepseek", "deepseek-v4-flash");
            yaml.put("ollama", "llama3.1");

            int created = service.seedFromYamlIfEmpty(yaml);

            assertThat(created).isEqualTo(2);  // deepseek 跳过
            verify(repo, times(2)).save(any(AiModelCatalog.class));
        }

        @Test
        @DisplayName("catalog 空 + yaml 也空 → 返回 0,无副作用")
        void no_yaml_no_seed() {
            when(repo.count()).thenReturn(0L);

            int created = service.seedFromYamlIfEmpty(Map.of());

            assertThat(created).isZero();
            verify(repo, never()).save(any());
        }
    }

    // ─────────────────── helper ───────────────────

    private static AiModelCatalog newModel(Long id, String vendor, String name, boolean enabled) {
        AiModelCatalog m = new AiModelCatalog();
        try {
            var f = AiModelCatalog.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (Exception e) { /* test only */ }
        m.setVendor(vendor);
        m.setModelName(name);
        m.setEnabled(enabled);
        m.setSupportsTools(true);
        m.setSupportsStreaming(true);
        m.setTier("STANDARD");
        return m;
    }
}
