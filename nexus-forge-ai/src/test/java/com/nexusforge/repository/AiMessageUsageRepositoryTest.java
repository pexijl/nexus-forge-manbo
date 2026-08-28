package com.nexusforge.repository;

import com.nexusforge.entity.AiConversation;
import com.nexusforge.entity.AiMessage;
import com.nexusforge.entity.AiMessageUsage;
import com.nexusforge.service.UsageAggregateRow;
import com.nexusforge.service.UsageByModelRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 Step 1: 聚合查询 JPQL 真实 SQL 跑测。
 *
 * <p><b>Spring Boot 4.1 兼容</b>:Spring Boot 4 移除了 {@code @DataJpaTest} 切片。
 * JPA 实体发现走 {@code @EntityScan} (Boot 4.1 包路径变更为
 * {@code org.springframework.boot.persistence.autoconfigure.EntityScan})。
 *
 * <p>5 个 {@code @Nested} 用例:
 * <ol>
 *   <li>{@code EmptyUser} — 用户无任何用量,聚合返回全 0;</li>
 *   <li>{@code SingleMessage} — 1 条消息,聚合等于该消息用量;</li>
 *   <li>{@code MultiMessageMultiModel} — 多 model 多消息,by-model 拆分且按 totalTokens 降序;</li>
 *   <li>{@code TimeWindow} — 时间窗外不计入,边界含 / 排他;</li>
 *   <li>{@code CrossConversationAggregate} — 同一 user 多 session,sumByUser 跨会话累加,user 间隔离;</li>
 * </ol>
 */
@SpringBootTest(classes = AiMessageUsageRepositoryTest.TestApp.class)
@Testcontainers
class AiMessageUsageRepositoryTest {

    /**
     * 最小 Spring 上下文。
     * <ul>
     *   <li>{@code scanBasePackages = "com.nexusforge.repository"} — 只扫仓库包,避免
     *       触发 AI 模块的 controller / ChatModel 装配(那些要 OpenAI 真实 key);</li>
     *   <li>{@code @EnableJpaRepositories} — 仓库使能;</li>
     *   <li>{@code @EntityScan} — 显式声明实体所在包,因默认从应用根扫不到子包。</li>
     * </ul>
     */
    @SpringBootApplication(scanBasePackages = "com.nexusforge.repository")
    @EnableJpaRepositories(basePackages = "com.nexusforge.repository")
    @EntityScan(basePackages = "com.nexusforge.entity")
    static class TestApp { }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:latest")
                    .withDatabaseName("nexus_forge_test_repo")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // 禁用 Flyway 迁移(AI 模块在 production profile 才启用)
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private AiMessageUsageRepository usageRepo;
    @Autowired private AiMessageRepository messageRepo;
    @Autowired private AiConversationRepository convRepo;

    @AfterEach
    void cleanUp() {
        // 用量行是 1:1 依赖 message,先删 usage 再删 message。
        usageRepo.deleteAll();
        messageRepo.deleteAll();
        convRepo.deleteAll();
    }

    // ─────────────────────────────────────────────
    //  Fixture
    // ─────────────────────────────────────────────

    /**
     * 在数据库里建一个 user 拥有的对话,加 N 条消息 + N 条用量。返回对话 ID。
     * 消息 seq 从 0 起递增,createdAt 全部为 {@code now}(统一时间戳便于断言)。
     */
    private Long seedConversation(long userId, String title, List<UsageFixture> usages) {
        AiConversation c = new AiConversation();
        c.setUserId(userId);
        c.setTitle(title);
        c.setModel("openai:gpt-4o-mini");
        c.setPinned(false);
        convRepo.saveAndFlush(c);
        int seq = 0;
        for (UsageFixture f : usages) {
            AiMessage m = new AiMessage();
            m.setConversationId(c.getId());
            m.setRole("ASSISTANT");
            m.setContent("ok");
            m.setSeq(seq++);
            messageRepo.saveAndFlush(m);
            AiMessageUsage u = new AiMessageUsage();
            u.setMessageId(m.getId());
            u.setPromptTokens(f.promptTokens);
            u.setCompletionTokens(f.completionTokens);
            u.setTotalTokens(f.promptTokens + f.completionTokens);
            u.setModel(f.model);
            usageRepo.saveAndFlush(u);
        }
        return c.getId();
    }

    private record UsageFixture(String model, int promptTokens, int completionTokens) { }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ─────────────────────────────────────────────
    //  Cases
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("EmptyUser")
    class EmptyUser {

        @Test
        @DisplayName("用户无任何对话 → sumByUserAndWindow 返回全 0")
        void user_with_no_conversations_returns_zero() {
            OffsetDateTime from = nowUtc().minusDays(1);
            OffsetDateTime to = nowUtc().plusDays(1);

            UsageAggregateRow row = usageRepo.sumByUserAndWindow(999_999L, from, to);

            assertThat(row).isNotNull();
            assertThat(row.promptTokens()).isZero();
            assertThat(row.completionTokens()).isZero();
            assertThat(row.totalTokens()).isZero();
            assertThat(row.requestCount()).isZero();
        }

        @Test
        @DisplayName("用户无任何对话 → sumByUserModelWindow 返回空 list")
        void user_with_no_conversations_returns_empty_list() {
            List<UsageByModelRow> rows = usageRepo.sumByUserModelWindow(
                    999_999L, nowUtc().minusDays(1), nowUtc().plusDays(1));
            assertThat(rows).isEmpty();
        }
    }

    @Nested
    @DisplayName("SingleMessage")
    class SingleMessage {

        @Test
        @DisplayName("1 条消息 + 1 条用量 → 聚合等于该条用量")
        void single_message_aggregates_to_its_own_value() {
            long userId = 1001L;
            Long convId = seedConversation(userId, "single-msg", List.of(
                    new UsageFixture("openai:gpt-4o-mini", 12, 8)));

            UsageAggregateRow row = usageRepo.sumByUserAndWindow(
                    userId, nowUtc().minusHours(1), nowUtc().plusHours(1));

            assertThat(row.promptTokens()).isEqualTo(12L);
            assertThat(row.completionTokens()).isEqualTo(8L);
            assertThat(row.totalTokens()).isEqualTo(20L);
            assertThat(row.requestCount()).isEqualTo(1L);

            // sumByConversation 也等于同一行
            UsageAggregateRow byConv = usageRepo.sumByConversation(convId);
            assertThat(byConv.totalTokens()).isEqualTo(20L);
            assertThat(byConv.requestCount()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("MultiMessageMultiModel")
    class MultiMessageMultiModel {

        @Test
        @DisplayName("多 model 多消息 → by-model 拆分且按 totalTokens 降序")
        void by_model_aggregates_and_sorts_desc() {
            long userId = 2001L;
            seedConversation(userId, "multi-model", List.of(
                    new UsageFixture("openai:gpt-4o-mini", 10, 5),    // 15
                    new UsageFixture("openai:gpt-4o-mini", 20, 10),   // 30
                    new UsageFixture("anthropic:claude-3-5-haiku-20241022", 8, 12), // 20
                    new UsageFixture("ollama:llama3", 100, 0)));      // 100

            List<UsageByModelRow> rows = usageRepo.sumByUserModelWindow(
                    userId, nowUtc().minusHours(1), nowUtc().plusHours(1));

            assertThat(rows).hasSize(3);

            // 排序:ollama 100 > openai 45 > anthropic 20
            assertThat(rows.get(0).model()).isEqualTo("ollama:llama3");
            assertThat(rows.get(0).totalTokens()).isEqualTo(100L);
            assertThat(rows.get(0).requestCount()).isEqualTo(1L);

            assertThat(rows.get(1).model()).isEqualTo("openai:gpt-4o-mini");
            assertThat(rows.get(1).promptTokens()).isEqualTo(30L); // 10+20
            assertThat(rows.get(1).completionTokens()).isEqualTo(15L); // 5+10
            assertThat(rows.get(1).totalTokens()).isEqualTo(45L);
            assertThat(rows.get(1).requestCount()).isEqualTo(2L);

            assertThat(rows.get(2).model()).isEqualTo("anthropic:claude-3-5-haiku-20241022");
            assertThat(rows.get(2).totalTokens()).isEqualTo(20L);

            // 总用量 = 100 + 45 + 20 = 165
            UsageAggregateRow total = usageRepo.sumByUserAndWindow(
                    userId, nowUtc().minusHours(1), nowUtc().plusHours(1));
            assertThat(total.totalTokens()).isEqualTo(165L);
            assertThat(total.requestCount()).isEqualTo(4L);
        }
    }

    @Nested
    @DisplayName("TimeWindow")
    class TimeWindow {

        @Test
        @DisplayName("时间窗 [from, to) 边界:窗内命中、窗外排除")
        void time_window_excludes_outside() {
            long userId = 3001L;
            // 种子数据时间戳 = 现在
            seedConversation(userId, "tw", List.of(
                    new UsageFixture("openai:gpt-4o-mini", 5, 5)));

            // [now-1h, now+1h):当前 seed 在窗内 → 命中
            UsageAggregateRow inside = usageRepo.sumByUserAndWindow(
                    userId, nowUtc().minusHours(1), nowUtc().plusHours(1));
            assertThat(inside.totalTokens()).isEqualTo(10L);

            // [now+1h, now+2h):当前 seed 在窗外 → 不命中
            UsageAggregateRow outside = usageRepo.sumByUserAndWindow(
                    userId, nowUtc().plusHours(1), nowUtc().plusHours(2));
            assertThat(outside.totalTokens()).isZero();
            assertThat(outside.requestCount()).isZero();
        }
    }

    @Nested
    @DisplayName("CrossConversationAggregate")
    class CrossConversationAggregate {

        @Test
        @DisplayName("同一 user 多 session → sumByUser 跨会话累加;sumByConversation 仅本会话")
        void cross_conversation_aggregation() {
            long userId = 4001L;
            Long convA = seedConversation(userId, "A", List.of(
                    new UsageFixture("openai:gpt-4o-mini", 10, 10)));
            Long convB = seedConversation(userId, "B", List.of(
                    new UsageFixture("openai:gpt-4o-mini", 5, 5),
                    new UsageFixture("openai:gpt-4o-mini", 3, 2)));

            // sumByUser: 跨 convA + convB 累加
            UsageAggregateRow user = usageRepo.sumByUserAndWindow(
                    userId, nowUtc().minusHours(1), nowUtc().plusHours(1));
            assertThat(user.totalTokens()).isEqualTo(35L); // 20 + 10 + 5
            assertThat(user.requestCount()).isEqualTo(3L);

            // sumByConversation: 各自
            assertThat(usageRepo.sumByConversation(convA).totalTokens()).isEqualTo(20L);
            assertThat(usageRepo.sumByConversation(convB).totalTokens()).isEqualTo(15L);
        }

        @Test
        @DisplayName("不同 user 的用量互不干扰")
        void users_isolation() {
            long userA = 5001L;
            long userB = 5002L;
            seedConversation(userA, "A", List.of(
                    new UsageFixture("openai:gpt-4o-mini", 100, 100)));
            seedConversation(userB, "B", List.of(
                    new UsageFixture("openai:gpt-4o-mini", 1, 1)));

            UsageAggregateRow a = usageRepo.sumByUserAndWindow(
                    userA, nowUtc().minusHours(1), nowUtc().plusHours(1));
            UsageAggregateRow b = usageRepo.sumByUserAndWindow(
                    userB, nowUtc().minusHours(1), nowUtc().plusHours(1));

            assertThat(a.totalTokens()).isEqualTo(200L);
            assertThat(b.totalTokens()).isEqualTo(2L);
        }
    }
}
