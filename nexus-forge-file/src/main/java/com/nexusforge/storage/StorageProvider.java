package com.nexusforge.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

public interface StorageProvider {

    // ============ Bucket 管理 ============

    /**
     * 创建存储桶
     * @param bucketName 存储桶名称（需全局唯一，命名规则：小写字母、数字、连字符，长度3-63）
     */
    void createBucket(String bucketName);

    /**
     * 检查存储桶是否存在
     * @param bucketName 存储桶名称
     * @return true-存在，false-不存在
     */
    boolean bucketExists(String bucketName);

    /**
     * 删除存储桶（仅当桶为空时才能删除）
     * @param bucketName 存储桶名称
     */
    void deleteBucket(String bucketName);

    // ============ Object CRUD ============

    /**
     * 上传文件对象
     * @param bucketName 存储桶名称
     * @param key 对象键（文件在桶中的路径/名称）
     * @param input 文件输入流（调用方负责关闭）
     * @param contentLength 内容长度（字节）
     * @param contentType MIME类型（如 "application/octet-stream"）
     * @return 对象的ETag值（可用于校验完整性）
     */
    String upload(String bucketName, String key, InputStream input, long contentLength, String contentType);

    /**
     * 下载文件对象
     * @param bucketName 存储桶名称
     * @param key 对象键
     * @return 文件输入流（调用方负责关闭）
     */
    InputStream download(String bucketName, String key);

    /**
     * 删除单个文件对象
     * @param bucketName 存储桶名称
     * @param key 对象键
     */
    void delete(String bucketName, String key);

    /**
     * 批量删除文件对象
     * @param bucketName 存储桶名称
     * @param keys 对象键列表
     */
    void deleteBatch(String bucketName, List<String> keys);

    /**
     * 检查对象是否存在
     * @param bucketName 存储桶名称
     * @param key 对象键
     * @return true-存在，false-不存在
     */
    boolean exists(String bucketName, String key);

    // ============ 分片上传（大文件） ============

    /**
     * 初始化分片上传（适用于大于100MB的文件）
     * @param bucketName 存储桶名称
     * @param key 对象键
     * @param contentType MIME类型
     * @return 上传ID（用于后续分片上传和完成操作）
     */
    String initiateMultipartUpload(String bucketName, String key, String contentType);

    /**
     * 上传单个分片
     * @param bucketName 存储桶名称
     * @param key 对象键
     * @param uploadId 上传ID（由 initiateMultipartUpload 返回）
     * @param partNumber 分片编号（从1开始，按顺序上传）
     * @param partData 分片数据流
     * @param partSize 分片大小（字节），除最后一片外应大于等于5MB
     * @return 分片的ETag值（用于完成上传时提交）
     */
    String uploadPart(String bucketName, String key, String uploadId, int partNumber, InputStream partData, long partSize);

    /**
     * 完成分片上传（将所有分片合并为完整文件）
     * @param bucketName 存储桶名称
     * @param key 对象键
     * @param uploadId 上传ID
     * @param partETags 所有分片的ETag列表（顺序需与分片编号一致）
     * @return 合并后对象的ETag值
     */
    String completeMultipartUpload(String bucketName, String key, String uploadId, List<String> partETags);

    /**
     * 取消分片上传（清理已上传的分片）
     * @param bucketName 存储桶名称
     * @param key 对象键
     * @param uploadId 上传ID
     */
    void abortMultipartUpload(String bucketName, String key, String uploadId);

    // ============ 预签名 URL ============

    /**
     * 生成预签名上传URL（允许客户端直接上传文件到存储桶）
     * @param bucketName 存储桶名称
     * @param key 对象键
     * @param expiry URL有效期（如 Duration.ofHours(1)）
     * @return 预签名的PUT URL
     */
    String generatePresignedPutUrl(String bucketName, String key, Duration expiry);

    /**
     * 生成预签名下载URL（允许客户端直接下载文件）
     * @param bucketName 存储桶名称
     * @param key 对象键
     * @param expiry URL有效期（如 Duration.ofMinutes(30)）
     * @return 预签名的GET URL
     */
    String generatePresignedGetUrl(String bucketName, String key, Duration expiry);

    // ============ 厂商标识 ============

    /**
     * 获取存储厂商标识
     * @return 厂商字符串："minio" | "aws" | "aliyun" | "tencent"
     *         可用于运行时策略判断或日志记录
     */
    String vendor();
}
