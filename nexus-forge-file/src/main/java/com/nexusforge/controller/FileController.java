package com.nexusforge.controller;

import com.nexusforge.base.Result;
import com.nexusforge.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件控制器
 * <p>提供文件上传、下载、删除、分片上传等 REST API</p>
 */
@Tag(name = "文件管理", description = "文件上传、下载、删除、分片上传与预签名 URL")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 单文件上传结果
     */
    @Schema(description = "单文件上传响应体")
    public record UploadResult(
            @Schema(description = "对象存储 key（后续下载/删除/分片均依赖此 key）", example = "avatars/2026/07/05/abc.png")
            String key,
            @Schema(description = "文件字节数", example = "102400")
            long size
    ) {
    }

    // ------------------------------------------------------------------
    // 单文件上传 / 下载 / 删除
    // ------------------------------------------------------------------

    @Operation(
            summary = "单文件上传",
            description = "multipart/form-data；后端会做大小/MIME 校验；返回对象存储 key"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传成功"),
            @ApiResponse(responseCode = "400", description = "文件为空或超过大小限制", content = @Content),
            @ApiResponse(responseCode = "2005", description = "文件上传失败（存储后端错误）", content = @Content)
    })
    @PostMapping("/upload")
    public Result<UploadResult> upload(
            @Parameter(description = "上传的文件（multipart/form-data 字段名 file）", required = true)
            @RequestParam MultipartFile file) throws IOException {
        String key = fileService.upload(file);
        return Result.success(new UploadResult(key, file.getSize()));
    }

    @Operation(
            summary = "下载文件",
            description = "直接返回二进制流，Content-Disposition: attachment 触发浏览器下载"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "下载成功（application/octet-stream）"),
            @ApiResponse(responseCode = "404", description = "key 不存在", content = @Content)
    })
    @GetMapping("/download/{key:.+}")
    public void download(
            @Parameter(description = "对象存储 key（URL 编码）", example = "avatars/2026/07/05/abc.png", required = true)
            @PathVariable String key,
            HttpServletResponse resp) throws IOException {
        try (InputStream in = fileService.download(key)) {
            resp.setContentType("application/octet-stream");
            resp.setHeader("Content-Disposition",
                    "attachment; filename=" + URLEncoder.encode(
                            key.substring(key.lastIndexOf('/') + 1), StandardCharsets.UTF_8));
            in.transferTo(resp.getOutputStream());
        }
    }

    @Operation(summary = "删除单个文件")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功（key 不存在时也返回 200 幂等）")
    })
    @DeleteMapping("/{key:.+}")
    public Result<Void> delete(
            @Parameter(description = "对象存储 key", required = true)
            @PathVariable String key) {
        fileService.delete(key);
        return Result.success();
    }

    @Operation(
            summary = "批量删除文件",
            description = "请求体为 key 字符串数组；任一失败不影响其他"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功")
    })
    @DeleteMapping("/batch")
    public Result<Void> deleteBatch(
            @Parameter(description = "要删除的 key 列表", required = true,
                    schema = @Schema(type = "array", example = "[\"avatars/a.png\",\"docs/b.pdf\"]"))
            @RequestBody List<String> keys) {
        fileService.deleteBatch(keys);
        return Result.success();
    }

    // ------------------------------------------------------------------
    // 预签名 URL
    // ------------------------------------------------------------------

    @Operation(
            summary = "生成前端直传 PUT URL",
            description = "前端拿到 URL 后可直接 PUT 上传到对象存储，绕过后端中转"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回预签名 URL 字符串")
    })
    @GetMapping("/presigned/put")
    public Result<String> presignedPutUrl(
            @Parameter(description = "目标 key", required = true)
            @RequestParam String key,
            @Parameter(description = "URL 有效期（秒）", example = "600")
            @RequestParam(defaultValue = "600") int expirySeconds) {
        String putUrl = fileService.generatePresignedPutUrl(key, expirySeconds);
        return Result.success(putUrl);
    }

    @Operation(
            summary = "生成私有对象临时访问 URL",
            description = "用于私有 bucket 的临时访问，到期后 URL 失效"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回预签名 GET URL 字符串")
    })
    @GetMapping("/presigned/get")
    public Result<String> presignedGetUrl(
            @Parameter(description = "目标 key", required = true)
            @RequestParam String key,
            @Parameter(description = "URL 有效期（秒）", example = "3600")
            @RequestParam(defaultValue = "3600") int expirySeconds) {
        String getUrl = fileService.generatePresignedGetUrl(key, expirySeconds);
        return Result.success(getUrl);
    }

    // ------------------------------------------------------------------
    // 分片上传
    // ------------------------------------------------------------------

    @Operation(
            summary = "分片上传 - 初始化",
            description = "前端调起分片上传前先请求 uploadId；后续分片操作都依赖它"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回 uploadId 与 key")
    })
    @PostMapping("/multipart/init")
    public Result<Map<String, String>> initMultipart(
            @Parameter(description = "目标 key", required = true)
            @RequestParam String key,
            @Parameter(description = "文件 MIME 类型", example = "video/mp4", required = true)
            @RequestParam String contentType) {
        String uploadId = fileService.initMultipartUpload(key, contentType);
        Map<String, String> data = Map.of("uploadId", uploadId, "key", key);
        return Result.success(data);
    }

    @Operation(
            summary = "分片上传 - 申请分片预签名 URL",
            description = "前端拿到 URL 后用 PUT 上传该分片内容；返回的 ETag 需在 complete 时回传"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回该分片的预签名 URL 与 partNumber")
    })
    @PostMapping("/multipart/presign-part")
    public Result<Map<String, String>> presignPart(
            @Parameter(description = "目标 key", required = true)
            @RequestParam String key,
            @Parameter(description = "init 接口返回的 uploadId", required = true)
            @RequestParam String uploadId,
            @Parameter(description = "分片编号（从 1 开始）", example = "1")
            @RequestParam int partNumber,
            @Parameter(description = "URL 有效期（秒）", example = "3600")
            @RequestParam(defaultValue = "3600") int expiry) {
        String url = fileService.presignPartUrl(key, expiry);
        Map<String, String> data = new HashMap<>();
        data.put("url", url);
        data.put("partNumber", String.valueOf(partNumber));
        return Result.success(data);
    }

    @Operation(
            summary = "分片上传 - 合并",
            description = "前端在所有分片 PUT 完成后调用，把 partETags 按顺序传回，后端合并为最终对象"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回最终 location 与 key"),
            @ApiResponse(responseCode = "400", description = "ETag 数量不匹配或合并失败", content = @Content)
    })
    @PostMapping("/multipart/complete")
    public Result<Map<String, String>> completeMultipart(
            @Parameter(description = "目标 key", required = true)
            @RequestParam String key,
            @Parameter(description = "init 接口返回的 uploadId", required = true)
            @RequestParam String uploadId,
            @Parameter(description = "每个分片上传后返回的 ETag（按 partNumber 顺序）", required = true)
            @RequestBody List<String> partETags) {
        String location = fileService.completeMultipartUpload(key, uploadId, partETags);
        Map<String, String> data = Map.of("location", location, "key", key);
        return Result.success(data);
    }
}