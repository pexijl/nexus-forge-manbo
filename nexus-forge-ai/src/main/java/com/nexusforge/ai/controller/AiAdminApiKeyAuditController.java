package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.vo.VendorApiKeyAuditLogVo;
import com.nexusforge.ai.repository.AiApiKeyAuditLogRepository;
import com.nexusforge.base.PageResult;
import com.nexusforge.base.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI vendor 系统 API Key 轮换审计查询 Admin API(Phase 8)。
 *
 * <p>范围:admin 在后台查"谁在何时换了哪个 vendor 的 Key,改前改后 fingerprint 是啥"。
 * 写审计的端点(POST/PUT/DELETE)不在本 controller,跟 {@code AiAdminVendorController}
 * 的 apiKey 端点对应(那里是写,这里是读)。
 *
 * <p>路径:
 * <ul>
 *   <li>{@code GET /api/admin/ai/vendors/{vendor}/api-key-audit} — 查某 vendor 的所有变更</li>
 *   <li>{@code GET /api/admin/ai/api-key-audit} — 全表分页(管理员后台面板用)</li>
 * </ul>
 *
 * <p>分页规范:1-based {@code page} + {@code size}(对齐仓库 {@code PageResult} 约定),
 * 时间倒序;GIN 索引 {@code idx_ai_api_key_audit_log_metadata_vendor} 走 JSONB
 * {@code metadata->>'vendor'} 等值查询(迁移 V20260902_008)。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "AI Admin - API Key 审计", description = "管理员查询 system API Key 轮换审计(SET / CLEAR 历史)")
@PreAuthorize("hasRole('ADMIN')")
public class AiAdminApiKeyAuditController {

    private final AiApiKeyAuditLogRepository repo;

    /**
     * 查某 vendor 的所有 Key 变更(按时间倒序分页)。
     * <p>运营场景:某天发现 openai 调用异常,查"谁最近改过 openai 的 key / 改前改后指纹是啥"。
     */
    @Operation(summary = "查某 vendor 的 API Key 轮换历史(按时间倒序分页)")
    @GetMapping("/api/admin/ai/vendors/{vendor}/api-key-audit")
    public Result<PageResult<VendorApiKeyAuditLogVo>> listByVendor(
            @PathVariable String vendor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<com.nexusforge.ai.entity.AiApiKeyAuditLog> rows =
                repo.findByMetadataVendorOrderByCreatedAtDesc(vendor.toLowerCase(), pageable);
        PageResult<VendorApiKeyAuditLogVo> result = PageResult.of(
                rows.map(VendorApiKeyAuditLogVo::from));
        return Result.success(result);
    }

    /**
     * 全表分页(管理员后台审计面板:看最近所有 key 变更)。
     */
    @Operation(summary = "全表分页查所有 vendor 的 API Key 变更历史(按时间倒序)")
    @GetMapping("/api/admin/ai/api-key-audit")
    public Result<PageResult<VendorApiKeyAuditLogVo>> listAll(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<com.nexusforge.ai.entity.AiApiKeyAuditLog> rows = repo.findAll(pageable);
        PageResult<VendorApiKeyAuditLogVo> result = PageResult.of(
                rows.map(VendorApiKeyAuditLogVo::from));
        return Result.success(result);
    }
}
