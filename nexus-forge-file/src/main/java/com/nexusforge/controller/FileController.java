package com.nexusforge.controller;

import com.nexusforge.base.Result;
import com.nexusforge.config.StorageProperties;
import com.nexusforge.storage.StorageProvider;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件控制器
 * <p>提供文件上传、下载、删除等操作的 REST API 接口</p>
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final StorageProvider storageProvider;
    private final StorageProperties storageProps;

    public record UploadResult(String key, long size) {
    }

    @PostMapping("/upload")
    public Result<?> upload(@RequestParam MultipartFile file) throws IOException {
        // ★ ObjectKey 设计：日期分桶 + UUID 前缀，避免热点
        String datePath = LocalDate.now().toString().replace("-", "/");
        String key = String.format("%s/%s-%s", datePath, UUID.randomUUID(), file.getOriginalFilename());

        try (InputStream in = file.getInputStream()) {
            storageProvider.upload(storageProps.getActive().getBucket(), key, in,
                    file.getSize(), file.getContentType());
        }
        return Result.success(new UploadResult(key, file.getSize()));
    }

    /**
     * 下载文件
     */
    @GetMapping("/download/{key:.+}")
    public void download(@PathVariable String key, HttpServletResponse resp) throws IOException {
        try (InputStream in = storageProvider.download(storageProps.getActive().getBucket(), key)) {
            resp.setContentType("application/octet-stream");
            resp.setHeader("Content-Disposition",
                    "attachment; filename=" + URLEncoder.encode(
                            key.substring(key.lastIndexOf('/') + 1), StandardCharsets.UTF_8));
            in.transferTo(resp.getOutputStream());
        }
    }

    /**
     * 删除文件
     */
    @DeleteMapping("/{key:.+}")
    public void delete(@PathVariable String key) {
        storageProvider.delete(storageProps.getActive().getBucket(), key);
    }

    /**
     * 批量删除
     */
    @DeleteMapping("/batch")
    public void deleteBatch(@RequestBody List<String> keys) {
        storageProvider.deleteBatch(storageProps.getActive().getBucket(), keys);
    }

    /**
     * 生成前端直传 PUT URL
     */
    @GetMapping("/presigned/put")
    public Result<?> presignedPutUrl(@RequestParam String key,
                                     @RequestParam(defaultValue = "600") int expirySeconds) {
        String putUrl = storageProvider.generatePresignedPutUrl(storageProps.getActive().getBucket(),
                key, Duration.ofSeconds(expirySeconds));
        return Result.success(putUrl);
    }

    /**
     * 生成前端直传 GET URL（私有 bucket 临时访问）
     */
    @GetMapping("/presigned/get")
    public Result<?> presignedGetUrl(@RequestParam String key,
                                     @RequestParam(defaultValue = "3600") int expirySeconds) {
        String getUrl = storageProvider.generatePresignedGetUrl(storageProps.getActive().getBucket(),
                key, Duration.ofSeconds(expirySeconds));
        return Result.success(getUrl);
    }

    /**
     * 前端请求 init：后端返回 uploadId
     */
    @PostMapping("/multipart/init")
    public Result<?> initMultipart(@RequestParam String key,
                                             @RequestParam String contentType) {
        String uploadId = storageProvider.initiateMultipartUpload(storageProps.getActive().getBucket(), key, contentType);
        Map<String, String> data = Map.of("uploadId", uploadId, "key", key);
        return Result.success(data);
    }

    /**
     * 前端请求每个分片的签名 URL
     */
    @PostMapping("/multipart/presign-part")
    public Result<?> presignPart(@RequestParam String key,
                                           @RequestParam String uploadId,
                                           @RequestParam int partNumber,
                                           @RequestParam(defaultValue = "3600") int expiry) {
        // ★ 阿里云 OSS：直接用 AWS S3 SDK 的 presignPutObject + 自己拼 partNumber 参数
        String url = storageProvider.generatePresignedPutUrl(storageProps.getActive().getBucket(), key, Duration.ofSeconds(expiry));
        Map<String, String> data = Map.of("url", url, "partNumber", String.valueOf(partNumber));
        return Result.success(data);
    }

    /**
     * 前端请求 part 完成（报告 ETag）
     * 前端请求 complete：合并所有分片
     */
    @PostMapping("/multipart/complete")
    public Result<?> completeMultipart(@RequestParam String key,
                                                 @RequestParam String uploadId,
                                                 @RequestBody List<String> partETags) {
        String location = storageProvider.completeMultipartUpload(storageProps.getActive().getBucket(), key, uploadId, partETags);
        Map<String, String> data = Map.of("location", location, "key", key);
        return Result.success(data);
    }
}
