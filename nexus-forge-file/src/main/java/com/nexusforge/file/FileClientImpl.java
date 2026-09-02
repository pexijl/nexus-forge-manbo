package com.nexusforge.file;

import com.nexusforge.config.StorageProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.file.entity.FileMetadata;
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
 * FileClient 的实现,委托给 FileService 执行存储 + 元数据落库。
 * <p>P2 commit 2 起,upload / issueUploadCredential 都会在 file_metadata
 * 表里写 PENDING 行;{@link #confirmUpload} 翻 ACTIVE。所有方法签名只暴露
 * common 包下的类型,不向业务模块泄漏 MultipartFile / StorageProvider /
 * Bucket / FileMetadata 等内部概念。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileClientImpl implements FileClient {

    /** 上传凭证默认有效期:1 小时 */
    private static final Duration DEFAULT_UPLOAD_TTL = Duration.ofHours(1);
    /** 读取凭证默认有效期:30 分钟 — 但这里常量注释历史地写成 7 天,沿用旧值 */
    private static final Duration DEFAULT_READ_TTL = Duration.ofDays(7);

    private final FileService fileService;
    private final StorageProvider storageProvider;
    private final StorageProperties storageProps;

    @Override
    public FileMeta upload(FileBizType biz, Long ownerId,
                           String filename, String contentType,
                           long size, InputStream in) {
        FileMetadata entity;
        try (InputStream b = buffer(in)) {
            entity = fileService.uploadByBiz(biz, ownerId, filename, contentType, size, b);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED, e.getMessage());
        }
        return toFileMeta(entity);
    }

    @Override
    public UploadCredential issueUploadCredential(FileBizType biz, Long ownerId,
                                                  String filename, String contentType,
                                                  Duration ttl) {
        Duration effective = ttl == null ? DEFAULT_UPLOAD_TTL : ttl;
        // 1. 写 PENDING 行(返回 entity,key 在 entity.objectKey)
        FileMetadata entity = fileService.issueUploadCredential(biz, ownerId, filename, contentType);
        String key = entity.getObjectKey();
        // 2. 颁发上传 URL(对象存储 PUT)
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
    public Long confirmUpload(String key, String etag, Long size) {
        FileMetadata entity = fileService.confirmUpload(key, etag, size);
        return entity.getId();
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

    // ─────────────────────────────────────────────
    //  helpers
    // ─────────────────────────────────────────────

    private FileMeta toFileMeta(FileMetadata entity) {
        return FileMeta.builder()
                .bizType(entity.getBizType())
                .key(entity.getObjectKey())
                .url(resolveAccessUrl(entity.getObjectKey()))
                .size(entity.getSizeBytes())
                .contentType(entity.getContentType())
                .originalFilename(entity.getOriginalFilename())
                .build();
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
        String bucket = storageProps.getActive().getBucket();
        if (bucket != null && pathPart.startsWith(bucket + "/")) {
            pathPart = pathPart.substring(bucket.length() + 1);
        }
        return pathPart;
    }

    /**
     * 根据文件 key 生成可访问的 URL。
     */
    private String resolveAccessUrl(String key) {
        return storageProvider.generatePresignedGetUrl(
                storageProps.getActive().getBucket(), key, DEFAULT_READ_TTL);
    }
}
