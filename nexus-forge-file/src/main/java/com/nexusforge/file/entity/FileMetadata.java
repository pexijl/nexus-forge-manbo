package com.nexusforge.file.entity;

import com.nexusforge.base.BaseEntity;
import com.nexusforge.file.FileAccess;
import com.nexusforge.file.FileBizType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 文件元数据实体 —— 映射 {@code file_metadata} 表。
 *
 * <h3>职责</h3>
 * 业务可查"我上传过的文件";为 GDPR 真删路径提供 owner_id 维度的
 * 行级删除入口(见 {@code FileUserDataDeletionListener})。
 *
 * <h3>状态机</h3>
 * 详见 {@link FileStatus};{@code PENDING} → {@code ACTIVE} 在 confirm
 * 时由 service 层翻状态;{@code ACTIVE} → {@code DELETED} 由软删触发。
 *
 * <h3>软删除</h3>
 * 软删注解(<b>必须直接放在 {@code @Entity} 上</b>,不能只放在
 * {@code @MappedSuperclass BaseEntity} —— Hibernate 6 不从父类继承
 * 软删 SQL 改写语义):
 * <ul>
 *   <li>{@code @SQLDelete}     —— {@code repo.delete(entity)} 拦截并转
 *       {@code UPDATE file_metadata SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL},
 *       同时把 status 翻为 DELETED(否则查询过滤了 deleted_at 但 status 仍是 ACTIVE,
 *       业务侧看到状态不一致)</li>
 *   <li>{@code @SQLRestriction} —— 所有查询自动加 {@code WHERE deleted_at IS NULL}</li>
 * </ul>
 *
 * <p>真删(物理删除,GDPR 路径)走 {@code EntityManager.createNativeQuery}
 * 绕过本注解,参考 {@code ConversationService.restoreConversation} 模式。</p>
 *
 * <h3>唯一约束</h3>
 * {@code (bucket, object_key)} 联合唯一 —— 同一 bucket 下同一 object_key
 * 只能有一行。前端重试 confirm 时,后端做 upsert 而非 insert(避免
 * unique 冲突);具体在 service 层 {@code findFirstByBucketAndObjectKey}
 * 查 + 状态翻转。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@Entity
@Table(
    name = "file_metadata",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_file_metadata_bucket_key",
        columnNames = {"bucket", "object_key"}
    ),
    indexes = {
        @Index(name = "idx_file_metadata_owner_uploaded", columnList = "owner_id, created_at DESC"),
        @Index(name = "idx_file_metadata_biz_owner", columnList = "biz_type, owner_id")
    }
)
@SQLDelete(sql = "UPDATE file_metadata SET deleted_at = now(), status = 'DELETED', updated_at = now() "
        + "WHERE id = ? AND deleted_at IS NULL")
@SQLRestriction("deleted_at IS NULL")
public class FileMetadata extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(nullable = false, length = 100)
    private String bucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "biz_type", nullable = false, length = 32)
    private FileBizType bizType;

    /** 访问权限枚举来自 common 模块({@link com.nexusforge.file.FileAccess})。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FileAccess access;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 128)
    private String etag;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FileStatus status = FileStatus.PENDING;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    /**
     * 业务侧扩展字段(原始 S3 响应 / 自定义键值对);用 {@link SqlTypes#JSON} 映射
     * PostgreSQL JSONB,Hibernate 6 自动选型。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * 业务层确认上传完成的统一入口 —— 翻状态 + 写时间戳。
     * 幂等:已 ACTIVE 再调不报错。
     */
    public void markConfirmed(String etag, Long size) {
        if (this.status == FileStatus.ACTIVE) {
            return;
        }
        this.status = FileStatus.ACTIVE;
        this.confirmedAt = OffsetDateTime.now();
        if (etag != null) {
            this.etag = etag;
        }
        if (size != null && size > 0) {
            this.sizeBytes = size;
        }
    }
}
