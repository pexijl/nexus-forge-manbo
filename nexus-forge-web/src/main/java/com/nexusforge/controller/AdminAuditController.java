package com.nexusforge.controller;

import com.nexusforge.audit.OperationAuditLog;
import com.nexusforge.audit.OperationAuditLogRepository;
import com.nexusforge.audit.OperationAuditLogVo;
import com.nexusforge.base.PageResult;
import com.nexusforge.base.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 查操作审计端点 —— 仅 ADMIN 可访问。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code GET /api/admin/audit-logs} —— 多维过滤 + 分页</li>
 * </ul>
 *
 * <h3>过滤维度</h3>
 * <ul>
 *   <li>{@code userId} —— 某用户的全部操作</li>
 *   <li>{@code action} —— 精确匹配 action(如 "user.update")</li>
 *   <li>{@code resource} —— 资源类型(如 "user" / "file")</li>
 * </ul>
 *
 * <p>不在 admin_search 加 @Audited(自己审计自己,会无限循环)。</p>
 */
@Tag(name = "管理 - 审计", description = "ADMIN 视角的操作审计查询")
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final OperationAuditLogRepository repo;

    @Operation(
            summary = "查操作审计",
            description = "多维过滤 + 分页;userId / action / resource 任一可空,空表示不参与过滤"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "403", description = "非 ADMIN 无权")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public Result<PageResult<OperationAuditLogVo>> search(
            @Parameter(description = "按 userId 过滤(可选)") @RequestParam(required = false) Long userId,
            @Parameter(description = "按 action 精确匹配(可选,如 user.update)")
            @RequestParam(required = false) String action,
            @Parameter(description = "按 resource 过滤(可选,如 user / file)")
            @RequestParam(required = false) String resource,
            @Parameter(description = "1-based 页码", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResult<OperationAuditLog> pageResult = PageResult.of(
                repo.adminSearch(userId, action, resource, pageable));
        PageResult<OperationAuditLogVo> voPage = PageResult.of(
                pageResult.getRecords().stream().map(OperationAuditLogVo::from).toList(),
                pageResult.getTotal(), pageResult.getPage(), pageResult.getSize());
        return Result.success(voPage);
    }
}
