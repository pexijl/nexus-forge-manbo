package com.nexusforge.service;

import com.nexusforge.config.StorageProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.file.FileBizType;
import com.nexusforge.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 文件服务类
 */
@Service
@RequiredArgsConstructor
public class FileService {

    private final StorageProvider storageProvider;
    private final StorageProperties storageProps;

    /**
     * 通用文件上传，自动按日期分桶
     *
     * @param file 上传的文件
     * @return 文件在存储中的 key
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

    /**
     * 上传用户头像，走 avatar/ 业务前缀
     *
     * @param userId 用户 ID
     * @param file   头像文件
     * @return 头像文件的存储 key
     */
    public String uploadAvatar(Long userId, MultipartFile file) throws IOException {
        String ext = getExtension(file.getOriginalFilename());
        String key = String.format("avatar/%d/%s.%s", userId, UUID.randomUUID(), ext);

        try (InputStream in = file.getInputStream()) {
            storageProvider.upload(
                    storageProps.getActive().getBucket(),
                    key, in, file.getSize(), file.getContentType()
            );
        }
        return key;
    }

    /**
     * 上传工作区附件，走 attachment/ 业务前缀
     *
     * @param userId 用户 ID
     * @param file   附件文件
     * @return 附件的存储 key
     */
    public String uploadAttachment(Long userId, MultipartFile file) throws IOException {
        String key = String.format("attachment/%d/%s-%s", userId, UUID.randomUUID(), file.getOriginalFilename());

        try (InputStream in = file.getInputStream()) {
            storageProvider.upload(
                    storageProps.getActive().getBucket(),
                    key, in, file.getSize(), file.getContentType()
            );
        }
        return key;
    }

    /**
     * 获取文件下载流
     *
     * @param key 文件 key
     * @return 输入流（调用方负责关闭）
     */
    public InputStream download(String key) {
        return storageProvider.download(storageProps.getActive().getBucket(), key);
    }

    /**
     * 删除单个文件
     *
     * @param key 文件 key
     */
    public void delete(String key) {
        storageProvider.delete(storageProps.getActive().getBucket(), key);
    }

    /**
     * 批量删除文件
     *
     * @param keys 文件 key 列表
     */
    public void deleteBatch(List<String> keys) {
        storageProvider.deleteBatch(storageProps.getActive().getBucket(), keys);
    }

    /**
     * 根据完整 URL 删除文件（自动提取 key）
     *
     * @param url 文件完整 URL
     */
    public void deleteByUrl(String url) {
        String key = extractKeyFromUrl(url);
        storageProvider.delete(storageProps.getActive().getBucket(), key);
    }

    /**
     * 生成前端直传 PUT URL
     *
     * @param key           文件 key
     * @param expirySeconds URL 有效期（秒）
     * @return 预签名 PUT URL
     */
    public String generatePresignedPutUrl(String key, int expirySeconds) {
        return storageProvider.generatePresignedPutUrl(
                storageProps.getActive().getBucket(),
                key, Duration.ofSeconds(expirySeconds)
        );
    }

    /**
     * 生成前端直传 GET URL（私有 bucket 临时访问）
     *
     * @param key           文件 key
     * @param expirySeconds URL 有效期（秒）
     * @return 预签名 GET URL
     */
    public String generatePresignedGetUrl(String key, int expirySeconds) {
        return storageProvider.generatePresignedGetUrl(
                storageProps.getActive().getBucket(),
                key, Duration.ofSeconds(expirySeconds)
        );
    }

    /**
     * 初始化分片上传
     *
     * @param key         文件 key
     * @param contentType 文件 MIME 类型
     * @return uploadId，用于后续分片上传
     */
    public String initMultipartUpload(String key, String contentType) {
        return storageProvider.initiateMultipartUpload(
                storageProps.getActive().getBucket(), key, contentType
        );
    }

    /**
     * 获取分片上传的预签名 URL
     *
     * @param key           文件 key
     * @param expirySeconds URL 有效期（秒）
     * @return 预签名 PUT URL
     */
    public String presignPartUrl(String key, int expirySeconds) {
        return storageProvider.generatePresignedPutUrl(
                storageProps.getActive().getBucket(),
                key, Duration.ofSeconds(expirySeconds)
        );
    }

    /**
     * 完成分片上传
     *
     * @param key       文件 key
     * @param uploadId  分片上传的 uploadId
     * @param partETags 分片的 ETag 列表
     * @return 文件的最终访问 URL
     */
    public String completeMultipartUpload(String key, String uploadId, List<String> partETags) {
        return storageProvider.completeMultipartUpload(
                storageProps.getActive().getBucket(), key, uploadId, partETags
        );
    }

    /**
     * 获取文件扩展名
     *
     * @param filename 文件名
     * @return 扩展名（不含点），如果没有扩展名返回空字符串
     */
    private String getExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    /**
     * 从完整 URL 中提取文件 key
     *
     * @param url 文件完整 URL
     * @return 文件 key
     */
    private String extractKeyFromUrl(String url) {
        // 简单实现：从 URL 末尾提取 key
        // 生产环境建议根据 StorageProperties 中的 endpoint 做精确解析
        String baseUrl = storageProps.getActive().getEndpoint();
        if (url.startsWith(baseUrl)) {
            return url.substring(baseUrl.length() + 1); // 去掉 endpoint + bucket
        }
        // 兜底：取最后一个 / 之后的部分作为 key（可能不准确，视 URL 格式调整）
        int lastSlash = url.lastIndexOf('/');
        return lastSlash != -1 ? url.substring(lastSlash + 1) : url;
    }


    /**
     * 按业务类型生成对象 key（仅生成路径，不实际上传）。
     * <p>key 形如：{prefix}/{ownerId}/{uuid}.{ext}。filename 无扩展名时
     * 省略 ".{ext}" 部分。ownerId 为空时使用 "anon"。</p>
     *
     * <p>与 {@link #uploadAvatar} / {@link #uploadAttachment} 路径规则一致，
     * 供 {@code FileClientImpl} 在"颁发直传凭证"等不需要文件流的场景复用。</p>
     *
     * @param biz      业务类型（必填）
     * @param ownerId  业务所有者 ID（可空，空时用 "anon"）
     * @param filename 原始文件名（必填，用于推断扩展名）
     * @return 对象 key
     */
    public String buildKeyForBiz(FileBizType biz, Long ownerId, String filename) {
        if (biz == null) {
            throw new BusinessException(ResultCode.FILE_BIZ_TYPE_IS_EMPTY);
        }
        String prefix = switch (biz) {
            case AVATAR -> "avatar";
            case ATTACHMENT -> "attachment";
            case AI_IMAGE -> "ai-image";
            case WORK_EXPORT -> "work-export";
        };
        String owner = ownerId == null ? "anon" : String.valueOf(ownerId);
        String ext = getExtension(filename);
        String namePart = UUID.randomUUID().toString()
                + (ext.isEmpty() ? "" : "." + ext);
        return String.format("%s/%s/%s", prefix, owner, namePart);
    }

    /**
     * 按业务类型上传文件（不依赖 MultipartFile）。
     * <p>业务模块拿到 InputStream 时调用；key 规则与
     * {@link #buildKeyForBiz} 保持一致。</p>
     */
    public String uploadByBiz(FileBizType biz, Long ownerId,
                              String filename, String contentType,
                              long size, InputStream in) throws IOException {
        String key = buildKeyForBiz(biz, ownerId, filename);
        storageProvider.upload(
                storageProps.getActive().getBucket(),
                key, in, size, contentType
        );
        return key;
    }
}
