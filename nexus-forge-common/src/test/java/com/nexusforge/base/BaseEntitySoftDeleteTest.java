package com.nexusforge.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BaseEntity} 软删除字段基础测试 —— 不依赖 Hibernate,只验证
 * 字段读写 + {@link BaseEntity#isDeleted()} 行为。
 *
 * <p>{@code @SQLDelete} / {@code @SQLRestriction} 的端到端行为在
 * {@code ConversationServiceTest} / {@code ConversationIT} 覆盖
 * (用真 PG + JPA 验证 SQL 改写)。</p>
 */
@DisplayName("BaseEntity 软删除基础")
class BaseEntitySoftDeleteTest {

    @Test
    @DisplayName("新建实体 deletedAt = null → isDeleted() = false")
    void new_entity_is_not_deleted() {
        BaseEntity entity = new BaseEntity();
        assertThat(entity.getDeletedAt()).isNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("设置 deletedAt 后 → isDeleted() = true")
    void deleted_entity() {
        BaseEntity entity = new BaseEntity();
        entity.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(entity.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("恢复:把 deletedAt 置回 null → isDeleted() = false")
    void restored_entity() {
        BaseEntity entity = new BaseEntity();
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        entity.setDeletedAt(past);
        assertThat(entity.isDeleted()).isTrue();

        entity.setDeletedAt(null);
        assertThat(entity.isDeleted()).isFalse();
        // 时间戳本身没被覆盖(由 @SQLDelete 内部触发或 service 显式置 null)
        assertThat(entity.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("审计字段 createdAt/updatedAt 独立于 deletedAt(soft delete 不动它们)")
    void audit_fields_independent() {
        BaseEntity entity = new BaseEntity();
        OffsetDateTime created = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        OffsetDateTime updated = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setCreatedAt(created);
        entity.setUpdatedAt(updated);
        entity.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(entity.getCreatedAt()).isEqualTo(created);
        assertThat(entity.getUpdatedAt()).isEqualTo(updated);
        assertThat(entity.isDeleted()).isTrue();
    }
}
