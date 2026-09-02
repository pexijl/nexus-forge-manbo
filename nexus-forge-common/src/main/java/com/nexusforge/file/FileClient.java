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
     * 前端直传完成后的确认回调 —— 把 PENDING 行翻为 ACTIVE。
     *
     * <p>调用方在 PUT 完成后(拿到 ETag 与最终 size)调用本方法;幂等:
     * 已 ACTIVE 行 no-op;不存在 → 抛 NOT_FOUND;已 DELETED → 抛
     * ALREADY_DELETED。</p>
     *
     * <p>本方法仅翻元数据状态;对象存储本身由前端 PUT 写入,后端不重复上传。</p>
     *
     * @param key  对象存储 key(同 {@code FileMeta.key} / 上传凭证的 publicUrl 中)
     * @param etag 对象返回的 ETag(可空,空时跳过 etag 回填)
     * @param size 实际字节数(可空,空时跳过 size 校正)
     * @return 翻状态后的元数据 id
     * @since P2 commit 2
     */
    Long confirmUpload(String key, String etag, Long size);

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
