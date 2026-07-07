package com.nexusforge.file;

import java.io.InputStream;
import java.time.Duration;

/**
 * 文件客户端
 * <p>业务模块只依赖此接口，不直接依赖具体实现。</p>
 */
public interface FileClient {
    /**
     * 业务文件流式上传
     *
     * @param biz         业务类型
     * @param ownerId     所有者ID
     * @param filename    文件名
     * @param contentType 内容类型
     * @param size        文件大小
     * @param in          文件输入流
     * @return 文件元数据
     */
    FileMeta upload(FileBizType biz, Long ownerId,
                    String filename, String contentType,
                    long size, InputStream in);

    /**
     * 颁发前端直传凭证（前端 PUT 到对象存储）
     *
     * @param biz         业务类型
     * @param ownerId     所有者ID
     * @param filename    文件名
     * @param contentType 内容类型
     * @param ttl         凭证有效期
     * @return 上传凭证
     */
    UploadCredential issueUploadCredential(FileBizType biz, Long ownerId,
                                           String filename, String contentType,
                                           Duration ttl);

    /**
     * 颁发前端读取凭证（私有对象），默认有效期为 7 天
     * @param key 文件key
     * @return 读取凭证URL
     */
    default String issueReadUrl(String key) {
        return issueReadUrl(key, Duration.ofDays(7));   // default TTL
    }

    /**
     * 颁发前端读取凭证（私有对象）
     *
     * @param key 文件key
     * @param ttl 凭证有效期
     * @return 读取凭证URL
     */
    String issueReadUrl(String key, Duration ttl);

    /**
     * 按业务 key 删除（key 由本接口之前返回）
     *
     * @param key 文件key
     */
    void delete(String key);

    /**
     * 按完整URL删除（自动提取 key）
     *
     * @param url 文件URL
     */
    void deleteByUrl(String url);
}
