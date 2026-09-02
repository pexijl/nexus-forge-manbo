package com.nexusforge.audit;

import com.nexusforge.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * 操作审计实体 —— 映射 {@code operation_audit_log} 表。
 *
 * <p><b>与 {@code AccountLifecycleLog} 的区别</b>:</p>
 * <ul>
 *   <li>{@code AccountLifecycleLog} 记录高粒度<b>业务事件</b>(BAN / DELETE_REQUEST /
 *       RESTORE / HARD_DELETE) + 业务 metadata,语义层</li>
 *   <li>{@code OperationAuditLog} 记录<b>HTTP 请求</b>(谁在什么 IP 调了什么端点、
 *       状态码、延迟、UA),传输层;由 {@code @Audited} 注解 + AOP 切面自动写</li>
 * </ul>
 *
 * <p>两表并存:业务事件表给业务回溯(状态机),审计表给安全 / 合规 / 性能分析。</p>
 *
 * <p>继承 {@link BaseEntity} 仅借用审计时间戳(createdAt / updatedAt)语义,
 * 本表<b>不软删</b>(审计行只追加,合规追溯需要物理持久)。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@Entity
@Table(
    name = "operation_audit_log",
    indexes = {
        @Index(name = "idx_op_audit_log_user_created", columnList = "user_id, created_at DESC"),
        @Index(name = "idx_op_audit_log_resource", columnList = "resource, resource_id, created_at DESC"),
        @Index(name = "idx_op_audit_log_created", columnList = "created_at DESC")
    }
)
public class OperationAuditLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 64)
    private String resource;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(nullable = false, length = 8)
    private String method;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(length = 45)
    private String ip;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditResult result;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "error_code")
    private Integer errorCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * 审计结果三态 —— 简化判定逻辑(SUCCESS / FAILURE 二元足够,
     * 不用 4 态 SUCCESS / FAILURE / TIMEOUT / ABORTED)。
     */
    public enum AuditResult {
        SUCCESS,
        FAILURE
    }
}
