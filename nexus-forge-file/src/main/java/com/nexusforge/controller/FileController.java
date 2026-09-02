package com.nexusforge.controller;

import com.nexusforge.base.PageResult;
import com.nexusforge.base.Result;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.dto.ConfirmUploadDto;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;
import com.nexusforge.file.vo.FileMetadataVo;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 文件控制器 —— 上传 / 下载 / 删除 / 分片 / 预签名 / 元数据。
 *
 * <p>P2 commit 3 增项:
 * <ul>
 *   <li>{@code POST /upload}        — 必填 biz + 自动从 security 取 owner;落元数据</li>
 *   <li>{@code POST /confirm/{key}} — 前端直传完成 confirm 回调</li>
 *   <li>{@code GET /mine}           — 当前用户文件分页(可选 biz 过滤)</li>
 *   <li>{@code GET /{id}}           — 单文件详情(带 owner 校验)</li>
 *   <li>{@code DELETE /{id}}        — 软删(带 owner 校验)</li>
 *   <li>{@code GET /admin}          — {@code @PreAuthorize("hasRole('ADMIN')")} 管理员视图</li>
 * </ul>
 * 历史端点(presigned/put / presigned/get / multipart/*)签名不变;旧的
 * 无 biz 上传路径(系统级)由 commit 5 IT 配合 biz 必填做迁移。
 */
@Tag(name = "文件管理", description = "文件上传、下载、删除、分片上传、预签名 URL 与元数据")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    // ─────────────────────────────────────────────
    //  上传 / confirm
    // ─────────────────────────────────────────────

    @Operation(
            summary = "上传文件(biz 必填)",
            description = "multipart/form-data;后端做大小/MIME 校验;返回对象存储 key + 落元数据(ACTIVE)"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传成功"),
            @ApiResponse(responseCode = "400", description = "biz 缺失 / 文件过大"),
            @ApiResponse(responseCode = "2005", description = "存储后端错误", content = @Content)
    })
    @PostMapping("/upload")
    @com.nexusforge.audit.Audited(
            value = "file.upload",
            resource = "file",
            recordArgs = true)
    public Result<FileMetadataVo> upload(
            @Parameter(description = "上传文件", required = true) @RequestParam MultipartFile file,
            @Parameter(description = "业务类型(AVATAR/ATTACHMENT/AI_IMAGE/WORK_EXPORT)", required = true)
            @RequestParam FileBizType biz) throws IOException {
        Long ownerId = currentUserId();
        if (ownerId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        FileMetadata entity = fileService.uploadByBiz(biz, ownerId,
                file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file.getInputStream());
        return Result.success(FileMetadataVo.from(entity));
    }

    @Operation(
            summary = "前端直传完成 confirm",
            description = "前端 PUT 到 S3 完成后回调;翻 PENDING → ACTIVE;幂等"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "确认成功"),
            @ApiResponse(responseCode = "2017", description = "文件不存在"),
            @ApiResponse(responseCode = "2018", description = "文件已删除")
    })
    @PostMapping("/confirm")
    public Result<FileMetadataVo> confirmUpload(
            @Parameter(description = "对象存储 key", required = true)
            @RequestParam String key,
            @Valid @RequestBody ConfirmUploadDto dto) {
        FileMetadata entity = fileService.confirmUpload(key, dto.etag(), dto.size());
        return Result.success(FileMetadataVo.from(entity));
    }

    // ─────────────────────────────────────────────
    //  元数据查询 / 软删
    // ─────────────────────────────────────────────

    @Operation(summary = "我的文件", description = "分页查询当前用户上传的文件;可选 biz 过滤")
    @GetMapping("/mine")
    public Result<PageResult<FileMetadataVo>> listMine(
            @Parameter(description = "业务类型过滤(可选)") @RequestParam(required = false) FileBizType biz,
            @Parameter(description = "1-based 页码", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        Long ownerId = currentUserId();
        if (ownerId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResult<FileMetadata> result = fileService.findMyFiles(ownerId, biz, pageable);
        PageResult<FileMetadataVo> voPage = PageResult.of(
                result.getRecords().stream().map(FileMetadataVo::from).toList(),
                result.getTotal(), result.getPage(), result.getSize());
        return Result.success(voPage);
    }

    @Operation(summary = "单文件详情", description = "带 owner 校验;非 owner 返回 FILE_FORBIDDEN")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "2017", description = "文件不存在"),
            @ApiResponse(responseCode = "2019", description = "非 owner 无权查看")
    })
    @GetMapping("/{id}")
    public Result<FileMetadataVo> getById(
            @Parameter(description = "文件 ID", required = true) @PathVariable Long id) {
        Long ownerId = currentUserId();
        FileMetadata entity = fileService.findByIdForOwner(id, ownerId)
                .orElseThrow(() -> new BusinessException(ResultCode.FILE_NOT_FOUND, "id=" + id));
        return Result.success(FileMetadataVo.from(entity));
    }

    @Operation(summary = "软删文件", description = "带 owner 校验;走 @SQLDelete 翻 DELETED")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "2017", description = "文件不存在"),
            @ApiResponse(responseCode = "2019", description = "非 owner 无权删除")
    })
    @DeleteMapping("/{id}")
    @com.nexusforge.audit.Audited(
            value = "file.delete",
            resource = "file",
            resourceId = "#id")
    public Result<Void> deleteById(
            @Parameter(description = "文件 ID", required = true) @PathVariable Long id) {
        Long ownerId = currentUserId();
        fileService.softDeleteById(id, ownerId);
        return Result.success();
    }

    // ─────────────────────────────────────────────
    //  管理员视角
    // ─────────────────────────────────────────────

    @Operation(
            summary = "管理员查文件(按 owner)",
            description = "ADMIN 限定;支持 owner / biz / status 三维过滤"
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin")
    public Result<PageResult<FileMetadataVo>> adminSearch(
            @Parameter(description = "ownerId 过滤(必填,管理员查的是某人)", required = true)
            @RequestParam Long ownerId,
            @Parameter(description = "biz 过滤(可选)") @RequestParam(required = false) FileBizType biz,
            @Parameter(description = "status 过滤(可选)") @RequestParam(required = false) FileStatus status,
            @Parameter(description = "1-based 页码", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResult<FileMetadata> result = fileService.adminSearch(ownerId, biz, status, pageable);
        PageResult<FileMetadataVo> voPage = PageResult.of(
                result.getRecords().stream().map(FileMetadataVo::from).toList(),
                result.getTotal(), result.getPage(), result.getSize());
        return Result.success(voPage);
    }

    // ─────────────────────────────────────────────
    //  历史端点(presigned/put / presigned/get / multipart / download)
    //  — 保留原签名,业务模块仍可调用。presigned/put 现在落 PENDING 元数据行。
    // ─────────────────────────────────────────────

    /**
     * 单文件上传结果(老路径,multipart 但不带 biz 的系统级上传)。
     * 业务上传请走 {@code POST /upload}。
     */
    @Schema(description = "单文件上传响应体(老路径)")
    public record LegacyUploadResult(
            @Schema(description = "对象存储 key") String key,
            @Schema(description = "文件字节数") long size
    ) { }

    @Operation(
            summary = "单文件上传(老路径,无 biz/owner)",
            description = "multipart/form-data;返回 key 但不落元数据。新业务请用 POST /upload(biz 必填)。"
    )
    @PostMapping("/upload-legacy")
    public Result<LegacyUploadResult> uploadLegacy(
            @Parameter(description = "上传的文件", required = true) @RequestParam MultipartFile file) throws IOException {
        String key = fileService.upload(file);
        return Result.success(new LegacyUploadResult(key, file.getSize()));
    }

    @Operation(summary = "下载文件", description = "返回二进制流")
    @GetMapping("/download/{key:.+}")
    public void download(
            @Parameter(description = "对象存储 key(URL 编码)", required = true) @PathVariable String key,
            HttpServletResponse resp) throws IOException {
        try (InputStream in = fileService.download(key)) {
            resp.setContentType("application/octet-stream");
            resp.setHeader("Content-Disposition",
                    "attachment; filename=" + URLEncoder.encode(
                            key.substring(key.lastIndexOf('/') + 1), StandardCharsets.UTF_8));
            in.transferTo(resp.getOutputStream());
        }
    }

    @Operation(summary = "删除单个文件", description = "只清对象存储,不动 DB 元数据")
    @DeleteMapping("/object/{key:.+}")
    public Result<Void> deleteObject(
            @Parameter(description = "对象存储 key", required = true) @PathVariable String key) {
        fileService.delete(key);
        return Result.success();
    }

    @Operation(summary = "批量删除文件", description = "请求体为 key 字符串数组")
    @DeleteMapping("/object/batch")
    public Result<Void> deleteObjectBatch(@RequestBody java.util.List<String> keys) {
        fileService.deleteBatch(keys);
        return Result.success();
    }

    @Operation(
            summary = "生成前端直传 PUT URL(biz 必填,落 PENDING 元数据)",
            description = "前端 PUT 到 S3 后调 /confirm/{key} 翻 ACTIVE"
    )
    @GetMapping("/presigned/put")
    public Result<String> presignedPutUrl(
            @Parameter(description = "业务类型(必填)", required = true) @RequestParam FileBizType biz,
            @Parameter(description = "文件名(用于 key 生成)", required = true) @RequestParam String filename,
            @Parameter(description = "URL 有效期(秒)", example = "600")
            @RequestParam(defaultValue = "600") int expirySeconds) {
        Long ownerId = currentUserId();
        if (ownerId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        // 写 PENDING 行(返回 entity,key 在 entity.objectKey)
        FileMetadata entity = fileService.issueUploadCredential(biz, ownerId, filename, "application/octet-stream");
        // 颁发上传 URL
        String putUrl = fileService.generatePresignedPutUrl(entity.getObjectKey(), expirySeconds);
        return Result.success(putUrl);
    }

    @Operation(summary = "生成私有对象临时访问 URL")
    @GetMapping("/presigned/get")
    public Result<String> presignedGetUrl(
            @Parameter(description = "目标 key", required = true) @RequestParam String key,
            @Parameter(description = "URL 有效期(秒)", example = "3600")
            @RequestParam(defaultValue = "3600") int expirySeconds) {
        String getUrl = fileService.generatePresignedGetUrl(key, expirySeconds);
        return Result.success(getUrl);
    }

    // 分片上传(init / presign-part / complete)— 走老路径,无元数据
    @PostMapping("/multipart/init")
    public Result<java.util.Map<String, String>> initMultipart(
            @RequestParam String key, @RequestParam String contentType) {
        String uploadId = fileService.initMultipartUpload(key, contentType);
        return Result.success(java.util.Map.of("uploadId", uploadId, "key", key));
    }

    @PostMapping("/multipart/presign-part")
    public Result<java.util.Map<String, String>> presignPart(
            @RequestParam String key, @RequestParam String uploadId,
            @RequestParam int partNumber,
            @RequestParam(defaultValue = "3600") int expiry) {
        String url = fileService.presignPartUrl(key, expiry);
        java.util.Map<String, String> data = new java.util.HashMap<>();
        data.put("url", url);
        data.put("partNumber", String.valueOf(partNumber));
        return Result.success(data);
    }

    @PostMapping("/multipart/complete")
    public Result<java.util.Map<String, String>> completeMultipart(
            @RequestParam String key, @RequestParam String uploadId,
            @RequestBody java.util.List<String> partETags) {
        String location = fileService.completeMultipartUpload(key, uploadId, partETags);
        return Result.success(java.util.Map.of("location", location, "key", key));
    }

    // ─────────────────────────────────────────────
    //  helper
    // ─────────────────────────────────────────────

    /**
     * 手动从 SecurityContextHolder 拿 userId —— 不用
     * {@code @AuthenticationPrincipal} 是因为该注解依赖
     * {@code AuthenticationPrincipalArgumentResolver} 走 MVC dispatcher 链,
     * 在 unit test 直接调 / IT @Nested 上下文切换时不解析 → NPE。
     * 改手动拿,行为一致(参考 AccountDeletionController)。
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p.userId();
        }
        return null;
    }
}
