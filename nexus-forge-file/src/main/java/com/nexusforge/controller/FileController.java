package com.nexusforge.controller;

import com.nexusforge.base.Result;
import com.nexusforge.config.StorageProperties;
import com.nexusforge.service.FileService;
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

    private final FileService fileService;

    public record UploadResult(String key, long size) {
    }

    @PostMapping("/upload")
    public Result<?> upload(@RequestParam MultipartFile file) throws IOException {
        String key = fileService.upload(file);
        return Result.success(new UploadResult(key, file.getSize()));
    }


    /**
     * 下载文件
     */
    @GetMapping("/download/{key:.+}")
    public void download(@PathVariable String key, HttpServletResponse resp) throws IOException {
        try (InputStream in = fileService.download(key)) {
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
    public Result<Void> delete(@PathVariable String key) {
        fileService.delete(key);
        return Result.success();
    }

    /**
     * 批量删除
     */
    @DeleteMapping("/batch")
    public Result<Void> deleteBatch(@RequestBody List<String> keys) {
        fileService.deleteBatch(keys);
        return Result.success();
    }

    /**
     * 生成前端直传 PUT URL
     */
    @GetMapping("/presigned/put")
    public Result<?> presignedPutUrl(@RequestParam String key,
                                     @RequestParam(defaultValue = "600") int expirySeconds) {
        String putUrl = fileService.generatePresignedPutUrl(key, expirySeconds);
        return Result.success(putUrl);
    }

    /**
     * 生成前端直传 GET URL（私有 bucket 临时访问）
     */
    @GetMapping("/presigned/get")
    public Result<?> presignedGetUrl(@RequestParam String key,
                                     @RequestParam(defaultValue = "3600") int expirySeconds) {
        String getUrl = fileService.generatePresignedGetUrl(key, expirySeconds);
        return Result.success(getUrl);
    }

    /**
     * 前端请求 init：后端返回 uploadId
     */
    @PostMapping("/multipart/init")
    public Result<?> initMultipart(@RequestParam String key,
                                   @RequestParam String contentType) {
        String uploadId = fileService.initMultipartUpload(key, contentType);
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
        String url = fileService.presignPartUrl(key, expiry);
        Map<String, String> data = new HashMap<>();
        data.put("url", url);
        data.put("partNumber", String.valueOf(partNumber));
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
        String location = fileService.completeMultipartUpload(key, uploadId, partETags);
        Map<String, String> data = Map.of("location", location, "key", key);
        return Result.success(data);
    }
}
