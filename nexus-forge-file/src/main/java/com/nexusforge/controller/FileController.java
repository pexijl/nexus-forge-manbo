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
import java.util.List;
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

}
