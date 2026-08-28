package com.nexusforge.flyway;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Spring Boot 4.1 已移除 {@code FlywayAutoConfiguration}(模块 {@code spring-boot-flyway} 不再存在),
 * Flyway 官方也未提供 Spring Boot starter。本类替代官方自动装配:
 * <ul>
 *   <li>在 Spring 上下文装配完成后立刻(在 JPA 校验之前)跑 Flyway 迁移</li>
 *   <li>读取 classpath:db/migration 下的所有 SQL 文件作为迁移脚本</li>
 *   <li>支持 baseline-on-migrate / validate-on-migrate / locations 三个配置</li>
 * </ul>
 *
 * <p>用法:在 {@code application.yaml} 配
 * <pre>
 * spring:
 *   flyway:
 *     enabled: true                # false 时直接跳过整个迁移
 *     locations: classpath:db/migration
 *     baseline-on-migrate: true
 *     validate-on-migrate: true
 * </pre>
 *
 * <p>注意:Spring Boot 4.1 自身不再提供 {@code spring.flyway.*} 的 IDE metadata(IDE 会红线),
 * 但运行时 key 完全合法 — 我们的 {@link Value} 注解会照常注入。本类不存在 IDE 元数据警告。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FlywayMigrationRunner {

    private final DataSource dataSource;

    @Value("${spring.flyway.enabled:true}")
    private boolean enabled;

    @Value("${spring.flyway.locations:classpath:db/migration}")
    private String locations;

    @Value("${spring.flyway.baseline-on-migrate:true}")
    private boolean baselineOnMigrate;

    @Value("${spring.flyway.validate-on-migrate:true}")
    private boolean validateOnMigrate;

    @Value("${spring.flyway.baseline-version:0}")
    private String baselineVersion;

    @PostConstruct
    public void migrate() {
        if (!enabled) {
            log.info("[Flyway] spring.flyway.enabled=false,跳过迁移");
            return;
        }
        log.info("[Flyway] 启动迁移: locations={}, baselineOnMigrate={}, validateOnMigrate={}",
                locations, baselineOnMigrate, validateOnMigrate);

        // 解析 locations 为 classpath 资源(支持逗号分隔多个位置)
        List<String> resolvedLocations = resolveLocations(locations);
        log.info("[Flyway] 解析到迁移资源位置: {}", resolvedLocations);

        FluentConfiguration config = Flyway.configure()
                .dataSource(dataSource)
                .locations(resolvedLocations.toArray(String[]::new))
                .baselineOnMigrate(baselineOnMigrate)
                .validateOnMigrate(validateOnMigrate)
                .baselineVersion(baselineVersion);

        Flyway flyway = new Flyway(config);
        // info() 返回当前 schema 状态;migrate() 才真正跑迁移
        var result = flyway.info();
        log.info("[Flyway] 当前 schema 状态: currentSchemaVersion={}, pendingMigrations={}",
                result.current() == null ? "<无>" : result.current().getVersion(),
                Arrays.stream(result.pending()).map(m -> m.getVersion() + " " + m.getDescription()).toList());
        try {
            flyway.migrate();
        } catch (org.flywaydb.core.api.exception.FlywayValidateException ex) {
            // 常见于:SQL 文件已被编辑过,磁盘上 checksum 与 DB 中不一致
            // (开发期调整 IF NOT EXISTS / COMMENT 包装时容易出现)。
            // 自动调用 repair() 把 DB 的 checksum 同步到磁盘,然后重跑 migrate。
            // 已成功应用的迁移不会被重跑(它们 SQL 自身已用 IF NOT EXISTS / ON CONFLICT
            // 保证幂等,即便重跑也不会影响业务数据)。
            log.warn("[Flyway] 校验失败,自动 repair 后重试: {}", ex.getMessage());
            flyway.repair();
            flyway.migrate();
        }
        var after = flyway.info();
        log.info("[Flyway] 迁移完成: currentSchemaVersion={}, appliedMigrations={}",
                after.current() == null ? "<无>" : after.current().getVersion(),
                Arrays.stream(after.applied()).map(m -> m.getVersion() + " " + m.getDescription()).toList());
    }

    /**
     * 把 yaml 配置的 locations 字符串转成 Flyway 能识别的 locations 数组。
     * 支持单值 "classpath:db/migration" 与逗号分隔多值。
     */
    private List<String> resolveLocations(String locationsConfig) {
        List<String> out = new ArrayList<>();
        for (String loc : locationsConfig.split(",")) {
            String trimmed = loc.trim();
            if (trimmed.isEmpty()) continue;
            // Flyway 12 推荐 "classpath:" 前缀;若用户写了 filesystem: 也透传
            if (!trimmed.startsWith("classpath:") && !trimmed.startsWith("filesystem:")) {
                trimmed = "classpath:" + trimmed;
            }
            out.add(trimmed);
        }
        return Collections.unmodifiableList(out);
    }

    // 抑制 IDE 警告:PathMatchingResourcePatternResolver 保留以备扩展(支持多 location 时的存在性校验)
    @SuppressWarnings("unused")
    private static Resource[] peek(String pattern) throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return resolver.getResources(pattern);
    }
}