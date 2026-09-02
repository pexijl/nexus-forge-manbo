package com.nexusforge.file.vo;

import com.nexusforge.file.FileAccess;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;

import java.time.OffsetDateTime;

/**
 * 文件元数据视图对象 — 暴露给前端的只读投影。
 *
 * <p>不暴露 {@code bucket} / {@code etag} / {@code metadata} 等内部细节;
 * {@code objectKey} 在前端需要再次发起直传或 presigned GET 时通过
 * {@code /api/files/{id}/presigned-get} 拿 URL,不直接给 key。</p>
 */
public record FileMetadataVo(
        Long id,
        String key,
        FileBizType bizType,
        FileAccess access,
        Long ownerId,
        String originalFilename,
        String contentType,
        long sizeBytes,
        FileStatus status,
        OffsetDateTime confirmedAt,
        OffsetDateTime createdAt
) {

    public static FileMetadataVo from(FileMetadata entity) {
        return new FileMetadataVo(
                entity.getId(),
                entity.getObjectKey(),
                entity.getBizType(),
                entity.getAccess(),
                entity.getOwnerId(),
                entity.getOriginalFilename(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getStatus(),
                entity.getConfirmedAt(),
                entity.getCreatedAt()
        );
    }
}
