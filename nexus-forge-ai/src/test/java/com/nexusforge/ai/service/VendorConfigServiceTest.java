package com.nexusforge.ai.service;

import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.controller.dto.VendorConfigDto;
import com.nexusforge.ai.entity.AiVendorConfig;
import com.nexusforge.ai.event.VendorConfigChangedEvent;
import com.nexusforge.ai.repository.AiVendorConfigRepository;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2 — {@link VendorConfigService} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>findByVendor:DB 命中优先 / DB 未命中回退 yaml / 都不存在返回 null</li>
 *   <li>update:partial update(只传 enabled / 只传 baseUrl)/ 不存在抛错 / 事件类型分支</li>
 *   <li>seed:yaml 拷到 DB 跳过空 base-url / 已有跳过 / yaml 空跳过</li>
 *   <li>缓存命中:第一次走 DB,第二次缓存命中</li>
 *   <li>缓存失效:invalidateCache 后再次查询走 DB 拿新值</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VendorConfigService — Phase 2 vendor 配置")
class VendorConfigServiceTest {

    @Mock AiVendorConfigRepository repo;
    @Mock ApplicationEventPublisher publisher;
    @Mock ApiKeyCipher cipher;
    @Mock com.nexusforge.ai.audit.VendorApiKeyAuditLogger auditLogger;

    private VendorConfigService service;
    private AiProperties yamlProps;

    @BeforeEach
    void setUp() {
        yamlProps = new AiProperties();
        // yaml 配 openai / deepseek / ollama
        Map<String, AiProperties.Provider> providers = new HashMap<>();
        providers.put("openai",   provider(true, "https://api.openai.com/v1", "gpt-4o-mini"));
        providers.put("deepseek", provider(true, "https://api.deepseek.com",   "deepseek-v4-flash"));
        providers.put("ollama",   provider(false, "http://localhost:11434/v1", "llama3.1"));
        providers.put("no-url",   provider(true,  null,                       "x"));   // 测 seed 跳过
        // Phase 6 适配:yaml 也配 apiKey,用于 getEffectiveApiKey 的 yaml 兜底路径
        providers.get("openai").setApiKey("sk-yaml-openai");
        providers.get("deepseek").setApiKey("sk-yaml-deepseek");
        providers.get("ollama").setApiKey("sk-yaml-ollama");
        yamlProps.setProviders(providers);

        service = new VendorConfigService(repo, yamlProps, publisher, cipher, auditLogger);
    }

    // ─────────────────── findByVendor ───────────────────

    @Nested
    @DisplayName("findByVendor:DB 优先 + yaml 兜底")
    class FindByVendor {

        @Test
        @DisplayName("DB 命中 → 返回 DB 配置(enabled / baseUrl 来自 DB)")
        void db_hit_wins() {
            AiVendorConfig db = newCfg(1L, "openai", "https://db-override.example.com", false);
            when(repo.findByVendor("openai")).thenReturn(Optional.of(db));

            VendorConfigService.VendorConfigView v = service.findByVendor("openai");

            assertThat(v).isNotNull();
            assertThat(v.fromYaml()).isFalse();
            assertThat(v.entity().getBaseUrl()).isEqualTo("https://db-override.example.com");
            assertThat(v.entity().getEnabled()).isFalse();
        }

        @Test
        @DisplayName("DB 未命中 → 回退 yaml,fromYaml=true")
        void db_miss_falls_back_to_yaml() {
            when(repo.findByVendor("ollama")).thenReturn(Optional.empty());

            VendorConfigService.VendorConfigView v = service.findByVendor("ollama");

            assertThat(v).isNotNull();
            assertThat(v.fromYaml()).isTrue();
            assertThat(v.entity().getBaseUrl()).isEqualTo("http://localhost:11434/v1");
            assertThat(v.entity().getEnabled()).isFalse();
        }

        @Test
        @DisplayName("DB + yaml 都没有 → 返回 null")
        void both_miss_returns_null() {
            when(repo.findByVendor("unknown")).thenReturn(Optional.empty());

            assertThat(service.findByVendor("unknown")).isNull();
        }

        @Test
        @DisplayName("缓存命中:第二次不再查 DB")
        void cache_hit_avoids_db() {
            AiVendorConfig db = newCfg(1L, "openai", "https://x", true);
            when(repo.findByVendor("openai")).thenReturn(Optional.of(db));

            service.findByVendor("openai");
            service.findByVendor("openai");
            service.findByVendor("openai");

            verify(repo, times(1)).findByVendor("openai");
        }

        @Test
        @DisplayName("缓存失效后:再查走 DB 拿新值")
        void invalidation_refreshes() {
            AiVendorConfig old = newCfg(1L, "openai", "https://old", true);
            AiVendorConfig fresh = newCfg(1L, "openai", "https://new", true);
            when(repo.findByVendor("openai"))
                    .thenReturn(Optional.of(old))
                    .thenReturn(Optional.of(fresh));

            assertThat(service.findByVendor("openai").entity().getBaseUrl()).isEqualTo("https://old");

            service.invalidateCache("openai");

            assertThat(service.findByVendor("openai").entity().getBaseUrl()).isEqualTo("https://new");
        }
    }

    // ─────────────────── update ───────────────────

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("只改 baseUrl:发 UPDATED 事件")
        void partial_baseUrl_update() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://old", true);
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            VendorConfigDto dto = new VendorConfigDto();
            dto.setBaseUrl("https://new");

            AiVendorConfig updated = service.update("openai", dto);

            assertThat(updated.getBaseUrl()).isEqualTo("https://new");
            assertThat(updated.getEnabled()).isTrue();   // 保留

            ArgumentCaptor<VendorConfigChangedEvent> ev = ArgumentCaptor.forClass(VendorConfigChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType()).isEqualTo(VendorConfigChangedEvent.ChangeType.UPDATED);
        }

        @Test
        @DisplayName("只切 enabled:发 ENABLED_TOGGLED 事件(独立 type 便于审计)")
        void partial_enabled_toggle() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://x", true);
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            VendorConfigDto dto = new VendorConfigDto();
            dto.setEnabled(false);

            service.update("openai", dto);

            ArgumentCaptor<VendorConfigChangedEvent> ev = ArgumentCaptor.forClass(VendorConfigChangedEvent.class);
            verify(publisher).publishEvent(ev.capture());
            assertThat(ev.getValue().getChangeType())
                    .isEqualTo(VendorConfigChangedEvent.ChangeType.ENABLED_TOGGLED);
        }

        @Test
        @DisplayName("vendor 不在 DB → 抛 LLM_MODEL_NOT_FOUND,提示 yaml 配了再 seed")
        void unknown_vendor_throws() {
            when(repo.findByVendor("ghost")).thenReturn(Optional.empty());

            VendorConfigDto dto = new VendorConfigDto();
            dto.setBaseUrl("https://x");

            assertThatThrownBy(() -> service.update("ghost", dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_MODEL_NOT_FOUND.getCode());
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("baseUrl 传空字符串 → 抛 LLM_INVALID_REQUEST(不允许清空)")
        void blank_baseUrl_rejected() {
            AiVendorConfig existing = newCfg(1L, "openai", "https://x", true);
            when(repo.findByVendor("openai")).thenReturn(Optional.of(existing));

            VendorConfigDto dto = new VendorConfigDto();
            dto.setBaseUrl("   ");

            assertThatThrownBy(() -> service.update("openai", dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> ((BusinessException) e).getCode() == ResultCode.LLM_INVALID_REQUEST.getCode());
            verify(repo, never()).save(any());
        }
    }

    // ─────────────────── seed ───────────────────

    @Nested
    @DisplayName("seedFromYamlIfEmpty")
    class Seed {

        @Test
        @DisplayName("DB 非空 → 跳过 seed(不覆盖已有)")
        void skips_when_db_not_empty() {
            when(repo.count()).thenReturn(3L);

            int created = service.seedFromYamlIfEmpty();

            assertThat(created).isZero();
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("DB 空 + yaml 有 providers → 跳过 base-url 为空的 entry,其余全拷")
        void copies_valid_yaml() {
            when(repo.count()).thenReturn(0L);
            when(repo.save(any(AiVendorConfig.class))).thenAnswer(inv -> inv.getArgument(0));

            int created = service.seedFromYamlIfEmpty();

            // openai / deepseek / ollama 都有 base-url → 3 条;no-url 的 base-url=null → 跳过
            assertThat(created).isEqualTo(3);
            verify(repo, times(3)).save(any(AiVendorConfig.class));
        }

        @Test
        @DisplayName("DB 空 + yaml 空 → 0 条,无副作用")
        void empty_yaml_no_seed() {
            when(repo.count()).thenReturn(0L);
            yamlProps.setProviders(Map.of());

            int created = service.seedFromYamlIfEmpty();

            assertThat(created).isZero();
            verify(repo, never()).save(any());
        }
    }

    // ─────────────────── helper ───────────────────

    private static AiProperties.Provider provider(boolean enabled, String baseUrl, String defaultModel) {
        AiProperties.Provider p = new AiProperties.Provider();
        p.setEnabled(enabled);
        p.setBaseUrl(baseUrl);
        p.setDefaultModel(defaultModel);
        return p;
    }

    private static AiVendorConfig newCfg(Long id, String vendor, String baseUrl, boolean enabled) {
        AiVendorConfig m = new AiVendorConfig();
        try {
            var f = AiVendorConfig.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (Exception ignored) { /* test only */ }
        m.setVendor(vendor);
        m.setBaseUrl(baseUrl);
        m.setEnabled(enabled);
        return m;
    }
}
