package com.nexusforge.service;

import com.nexusforge.base.PageResult;
import com.nexusforge.config.StorageProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;
import com.nexusforge.file.repository.FileMetadataRepository;
import com.nexusforge.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件服务类
 *
 * <p>P2 commit 2 起,该服务同时管对象存储 + 元数据落库(file_metadata 表)。
 * 上传/凭证两条路径都会写 PENDING 行;前端 confirm 后翻 ACTIVE。
 * 业务可查"我上传过的文件"由 {@link #findMyFiles} 提供。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final StorageProvider storageProvider;
    private final StorageProperties storageProps;
    private final FileMetadataRepository fileRepo;
    private final com.nexusforge.lock.DistributedLockTemplate lockTemplate;

    // ─────────────────────────────────────────────
    //  通用上传(MultipartFile — controller 旧路径,无 biz / owner 概念)
    // ─────────────────────────────────────────────

    /**
     * 通用文件上传(系统级,无 biz / owner)。{@code FileController.upload} 旧调用方
     * 走这里;返回的 key 不入库。需要 biz/owner 的业务模块走
     * {@link #uploadByBiz} / {@link FileClient} 的对应方法。
     */
    public String upload(MultipartFile file) throws IOException {
        String datePath = LocalDate.now().toString().replace("-", "/");
        String key = String.format("%s/%s-%s", datePath, UUID.randomUUID(), file.getOriginalFilename());

        try (InputStream in = file.getInputStream()) {
            storageProvider.upload(
                    storageProps.getActive().getBucket(),
                    key, in, file.getSize(), file.getContentType()
            );
        }
        return key;
    }

    // ─────────────────────────────────────────────
    //  业务上传(走 file_metadata 落库)
    // ─────────────────────────────────────────────

    /**
     * 上传用户头像,走 avatar/ 业务前缀 + 入库。
     */
    public FileMetadata uploadAvatar(Long userId, MultipartFile file) throws IOException {
        return uploadByBiz(FileBizType.AVATAR, userId,
                file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file.getInputStream());
    }

    /**
     * 上传工作区附件,走 attachment/ 业务前缀 + 入库。
     */
    public FileMetadata uploadAttachment(Long userId, MultipartFile file) throws IOException {
        return uploadByBiz(FileBizType.ATTACHMENT, userId,
                file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file.getInputStream());
    }

    /**
     * 按业务类型上传(流式,不依赖 MultipartFile),同时落 PENDING → ACTIVE 行。
     *
     * <p>并发防护:同 (biz, ownerId) 同时只能有 1 个上传,锁 30s。
     * 防双 tab / 双击造成重复行 + 重复对象存储对象。anon 上传也走
     * "upload:{biz}:anon" 锁,防系统批跑撞车。拿不到锁抛
     * {@link com.nexusforge.exception.LockAcquireException} →
     * GlobalExceptionHandler 兜 409 FILE_UPLOAD_CONFLICT(2020)。</p>
     *
     * <p>事务边界:{@code @Transactional} 加在本方法上 —— 锁在事务内
     * 拿,锁释放先于事务 commit。微窗口期行已 commit 但锁未释放,
     * 但锁的存在保证别人并发上传时排队;本 holder 的 commit
     * 不被读未提交影响(其他线程看不到未 commit 的 PENDING)。</p>
     */
    @Transactional
    public FileMetadata uploadByBiz(FileBizType biz, Long ownerId,
                                     String filename, String contentType,
                                     long size, InputStream in) throws IOException {
        String lockKey = "upload:" + biz.name().toLowerCase()
                + ":" + (ownerId == null ? "anon" : ownerId);
        return lockTemplate.lock(lockKey, Duration.ofSeconds(30), () -> {
            try {
                return doUploadByBizInternal(biz, ownerId, filename, contentType, size, in);
            } catch (IOException e) {
                // 锁会因 supplier 抛错触发 finally 自动释放;
                // 把 IOException 包成业务异常让 GlobalExceptionHandler 接住
                throw new com.nexusforge.exception.BusinessException(
                        com.nexusforge.enums.ResultCode.FILE_UPLOAD_FAILED, e.getMessage());
            }
        });
    }

    /**
     * 实际工作方法 —— 拆出是为了避开 Spring AOP 的 self-invocation 限制
     * (lambda 内部 {@code this.doUploadByBiz(...)} 走不到外层 @Transactional
     * 的代理)。由 {@link #uploadByBiz} 包裹,锁释放走 lockTemplate 的 finally,
     * 事务 commit 走 @Transactional,两者互不嵌套。
     */
    private FileMetadata doUploadByBizInternal(FileBizType biz, Long ownerId,
                                                String filename, String contentType,
                                                long size, InputStream in) throws IOException {
        String key = buildKeyForBiz(biz, ownerId, filename);
        String bucket = storageProps.getActive().getBucket();

        // 1. 写 PENDING 行(占位 + 幂等)
        FileMetadata entity = upsertPending(bucket, key, biz, ownerId, filename, contentType, size);

        // 2. 上传到对象存储
        String etag = storageProvider.upload(bucket, key, in, size, contentType);

        // 3. 翻 ACTIVE + 写 etag
        entity.markConfirmed(etag, size);
        return fileRepo.save(entity);
    }

    /**
     * 颁发前端直传凭证(前端 PUT 到对象存储),同时写 PENDING 行。
     * 行不进 {@code @Transactional} 提交边界内的 storage 调用,因为没有 storage
     * 调用;但 DB 行 INSERT 仍在事务里,失败回滚不留垃圾。
     */
    @Transactional
    public FileMetadata issueUploadCredential(FileBizType biz, Long ownerId,
                                              String filename, String contentType) {
        String key = buildKeyForBiz(biz, ownerId, filename);
        String bucket = storageProps.getActive().getBucket();
        // 不需要 size — 前端 PUT 完成后 confirm 时再回填
        return upsertPending(bucket, key, biz, ownerId, filename, contentType, 0L);
    }

    /**
     * 前端直传完成后回调:翻 PENDING → ACTIVE。
     *
     * <p>幂等:已 ACTIVE 行 no-op(返回原 entity);不存在 → 抛 NOT_FOUND;
     * 已 DELETED → 抛 BUSINESS_ERROR(前端应避免对软删行 confirm)。</p>
     */
    @Transactional
    public FileMetadata confirmUpload(String key, String etag, Long size) {
        String bucket = storageProps.getActive().getBucket();
        FileMetadata entity = fileRepo.findByBucketAndObjectKey(bucket, key)
                .orElseThrow(() -> new BusinessException(ResultCode.FILE_NOT_FOUND,
                        "file key not found: " + key));
        if (entity.getStatus() == FileStatus.DELETED) {
            throw new BusinessException(ResultCode.FILE_ALREADY_DELETED,
                    "file already deleted: " + key);
        }
        entity.markConfirmed(etag, size);
        return fileRepo.save(entity);
    }

    /**
     * 「我的文件」分页。
     */
    public PageResult<FileMetadata> findMyFiles(Long ownerId, FileBizType biz,
                                                Pageable pageable) {
        Page<FileMetadata> page = (biz == null)
                ? fileRepo.findByOwnerIdAndStatusOrderByCreatedAtDesc(
                        ownerId, FileStatus.ACTIVE, pageable)
                : fileRepo.findByOwnerIdAndBizTypeAndStatusOrderByCreatedAtDesc(
                        ownerId, biz, FileStatus.ACTIVE, pageable);
        return PageResult.of(page);
    }

    /**
     * 管理员视角:按 owner 查(可跨 biz / status 过滤)。
     */
    public PageResult<FileMetadata> adminSearch(Long ownerId, FileBizType biz,
                                                FileStatus status, Pageable pageable) {
        return PageResult.of(fileRepo.adminSearch(ownerId, biz, status, pageable));
    }

    /**
     * 按 ID 查单行(业务层用,带 owner 校验)。返回的 entity 受 {@code @SQLRestriction}
     * 过滤(已软删的不返)。
     */
    public Optional<FileMetadata> findByIdForOwner(Long id, Long ownerId) {
        return fileRepo.findById(id).filter(f -> ownerId == null
                || f.getOwnerId() == null
                || f.getOwnerId().equals(ownerId));
    }

    /**
     * 软删(走 repo.delete 触发 @SQLDelete)。
     */
    @Transactional
    public void softDeleteById(Long id, Long ownerId) {
        FileMetadata f = fileRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.FILE_NOT_FOUND, "id=" + id));
        if (ownerId != null && f.getOwnerId() != null && !f.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ResultCode.FILE_FORBIDDEN, "not owner");
        }
        fileRepo.delete(f);
    }

    // ─────────────────────────────────────────────
    //  低层(不走 DB)
    // ─────────────────────────────────────────────

    public InputStream download(String key) {
        return storageProvider.download(storageProps.getActive().getBucket(), key);
    }

    public void delete(String key) {
        storageProvider.delete(storageProps.getActive().getBucket(), key);
    }

    public void deleteBatch(List<String> keys) {
        storageProvider.deleteBatch(storageProps.getActive().getBucket(), keys);
    }

    public void deleteByUrl(String url) {
        String key = extractKeyFromUrl(url);
        storageProvider.delete(storageProps.getActive().getBucket(), key);
    }

    public String generatePresignedPutUrl(String key, int expirySeconds) {
        return storageProvider.generatePresignedPutUrl(
                storageProps.getActive().getBucket(),
                key, Duration.ofSeconds(expirySeconds)
        );
    }

    public String generatePresignedGetUrl(String key, int expirySeconds) {
        return storageProvider.generatePresignedGetUrl(
                storageProps.getActive().getBucket(),
                key, Duration.ofSeconds(expirySeconds)
        );
    }

    public String initMultipartUpload(String key, String contentType) {
        return storageProvider.initiateMultipartUpload(
                storageProps.getActive().getBucket(), key, contentType
        );
    }

    public String presignPartUrl(String key, int expirySeconds) {
        return storageProvider.generatePresignedPutUrl(
                storageProps.getActive().getBucket(),
                key, Duration.ofSeconds(expirySeconds)
        );
    }

    public String completeMultipartUpload(String key, String uploadId, List<String> partETags) {
        return storageProvider.completeMultipartUpload(
                storageProps.getActive().getBucket(), key, uploadId, partETags
        );
    }

    // ─────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────

    /**
     * upsert PENDING 行:已存在且 status 仍是 PENDING 直接返回(凭证重发幂等);
     * 不存在就 insert。{@code (bucket, object_key)} 唯一约束由 DB 兜底,
     * 此处先查后插避免大部分冲突。
     */
    private FileMetadata upsertPending(String bucket, String key, FileBizType biz,
                                       Long ownerId, String filename, String contentType,
                                       long size) {
        return fileRepo.findByBucketAndObjectKey(bucket, key)
                .filter(f -> f.getStatus() == FileStatus.PENDING)
                .orElseGet(() -> {
                    FileMetadata f = new FileMetadata();
                    f.setBucket(bucket);
                    f.setObjectKey(key);
                    f.setBizType(biz);
                    f.setAccess(biz.defaultAccess());
                    f.setOwnerId(ownerId);
                    f.setOriginalFilename(filename);
                    f.setContentType(contentType);
                    f.setSizeBytes(size);
                    f.setStatus(FileStatus.PENDING);
                    return fileRepo.save(f);
                });
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    /**
     * 从完整 URL 中提取文件 key(简化实现,生产环境应精确解析)。
     */
    private String extractKeyFromUrl(String url) {
        String baseUrl = storageProps.getActive().getEndpoint();
        if (url.startsWith(baseUrl)) {
            return url.substring(baseUrl.length() + 1);
        }
        int lastSlash = url.lastIndexOf('/');
        return lastSlash != -1 ? url.substring(lastSlash + 1) : url;
    }

    /**
     * 按业务类型生成对象 key(仅生成路径,不实际上传)。key 形如
     * {@code {access}/{biz}/{ownerId}/{uuid}.{ext}};anon 时 ownerId 段为
     * "anon";filename 无扩展名省略 .{ext}。
     */
    public String buildKeyForBiz(FileBizType biz, Long ownerId, String filename) {
        if (biz == null) {
            throw new BusinessException(ResultCode.FILE_BIZ_TYPE_IS_EMPTY);
        }
        String bizPrefix = switch (biz) {
            case AVATAR -> "avatar";
            case ATTACHMENT -> "attachment";
            case AI_IMAGE -> "ai-image";
            case WORK_EXPORT -> "work-export";
        };
        String accessPrefix = switch (biz.defaultAccess()) {
            case PUBLIC  -> "public";
            case PRIVATE -> "private";
        };
        String ext = getExtension(filename);
        String owner = ownerId == null ? "anon" : String.valueOf(ownerId);
        return String.format("%s/%s/%s/%s%s",
                accessPrefix, bizPrefix, owner, UUID.randomUUID(), ext);
    }
}
