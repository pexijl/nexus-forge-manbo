package com.nexusforge.file;

import com.nexusforge.config.StorageProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.service.FileService;
import com.nexusforge.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * FileClient 的实现，委托给 FileService 执行业务上传/删除/凭证签发。
 * <p>所有方法签名只暴露 common 包下的类型，不向业务模块泄漏
 * MultipartFile / StorageProvider / Bucket 等内部概念。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileClientImpl implements FileClient {

    /**
     * 上传凭证默认有效期：1 小时
     */
    private static final Duration DEFAULT_UPLOAD_TTL = Duration.ofHours(1);
    /**
     * 读取凭证默认有效期：30 分钟
     */
    private static final Duration DEFAULT_READ_TTL = Duration.ofDays(7);

    private final FileService fileService;
    private final StorageProvider storageProvider;
    private final StorageProperties storageProps;

    @Override
    public FileMeta upload(FileBizType biz, Long ownerId,
                           String filename, String contentType,
                           long size, InputStream in) {
        String key;
        try (InputStream b = buffer(in)) {
            key = fileService.uploadByBiz(biz, ownerId, filename, contentType, size, b);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED, e.getMessage());
        }
        return FileMeta.builder()
                .bizType(biz)
                .key(key)
                .url(resolveAccessUrl(key))
                .size(size)
                .contentType(contentType)
                .originalFilename(filename)
                .build();
    }

    @Override
    public UploadCredential issueUploadCredential(FileBizType biz, Long ownerId,
                                                  String filename, String contentType,
                                                  Duration ttl) {
        Duration effective = ttl == null ? DEFAULT_UPLOAD_TTL : ttl;
        String key = fileService.buildKeyForBiz(biz, ownerId, filename);
        String uploadUrl = storageProvider.generatePresignedPutUrl(
                storageProps.getActive().getBucket(), key, effective);
        Map<String, String> headers = contentType == null
                ? Map.of() : Map.of("Content-Type", contentType);
        return UploadCredential.builder()
                .uploadUrl(uploadUrl)
                .publicUrl(resolveAccessUrl(key))
                .headers(headers)
                .expiresAt(Instant.now().plus(effective))
                .build();
    }

    @Override
    public String issueReadUrl(String key, Duration ttl) {
        Duration effective = ttl == null ? DEFAULT_READ_TTL : ttl;
        return storageProvider.generatePresignedGetUrl(
                storageProps.getActive().getBucket(), key, effective);
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) return;
        try {
            storageProvider.delete(storageProps.getActive().getBucket(), key);
        } catch (Exception e) {
            log.warn("删除文件失败 key={}, err={}", key, e.getMessage());
        }
    }

    @Override
    public void deleteByUrl(String url) {
        if (url == null || url.isBlank()) return;
        String key = extractKeyFromUrl(url);
        delete(key);
    }

    private InputStream buffer(InputStream in) {
        return in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
    }

    private String extractKeyFromUrl(String url) {
        int q = url.indexOf('?');
        if (q != -1) url = url.substring(0, q);
        int proto = url.indexOf("://");
        if (proto != -1) url = url.substring(proto + 3);
        int slash = url.indexOf('/');
        String pathPart = slash != -1 ? url.substring(slash + 1) : url;
        // 跳过 bucket 前缀（path-style URL 形如 endpoint/bucket/key）
        String bucket = storageProps.getActive().getBucket();
        if (bucket != null && pathPart.startsWith(bucket + "/")) {
            pathPart = pathPart.substring(bucket.length() + 1);
        }
        return pathPart;
    }

    /**
     * 根据业务类型和文件 key 生成可访问的 URL。
     *
     * @param key 文件 key
     * @return 可访问的 URL
     */
    private String resolveAccessUrl(String key) {
        return storageProvider.generatePresignedGetUrl(
                storageProps.getActive().getBucket(), key, DEFAULT_READ_TTL);
    }
}
