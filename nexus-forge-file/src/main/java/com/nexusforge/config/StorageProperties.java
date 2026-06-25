package com.nexusforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /**
     * 当前激活的存储厂商类型
     * <p>可选值：minio | aws | aliyun | tencent</p>
     * <p>默认值：minio</p>
     * <p>配置示例：storage.vendor=aliyun</p>
     */
    private String vendor = "minio";

    /**
     * MinIO 厂商配置映射
     * <p>Key: 配置名称（如 default, backup, test 等）</p>
     * <p>Value: 对应的厂商连接配置</p>
     * <p>配置示例：
     * <pre>
     * storage.minio.default.endpoint=http://localhost:9000
     * storage.minio.default.access-key=admin
     * storage.minio.default.secret-key=password
     * storage.minio.default.bucket=my-bucket
     * </pre>
     * </p>
     */
    private Map<String, VendorConfig> minio = new HashMap<>();

    /**
     * 阿里云 OSS 厂商配置映射
     * <p>Key: 配置名称（如 default, backup, test 等）</p>
     * <p>Value: 对应的厂商连接配置</p>
     * <p>配置示例：
     * <pre>
     * storage.aliyun.default.endpoint=oss-cn-hangzhou.aliyuncs.com
     * storage.aliyun.default.region=cn-hangzhou
     * storage.aliyun.default.access-key=LTAI4xxxxxx
     * storage.aliyun.default.secret-key=xxxxxxxx
     * storage.aliyun.default.bucket=my-bucket
     * storage.aliyun.default.cdn-domain=https://cdn.example.com
     * </pre>
     * </p>
     */
    private Map<String, VendorConfig> aliyun = new HashMap<>();

    /**
     * 腾讯云 COS 厂商配置映射
     * <p>Key: 配置名称（如 default, backup, test 等）</p>
     * <p>Value: 对应的厂商连接配置</p>
     * <p>配置示例：
     * <pre>
     * storage.tencent.default.region=ap-guangzhou
     * storage.tencent.default.secret-id=AKIDxxxxxx
     * storage.tencent.default.secret-key=xxxxxxxx
     * storage.tencent.default.bucket=my-bucket-1234567890
     * </pre>
     * </p>
     */
    private Map<String, VendorConfig> tencent = new HashMap<>();

    /**
     * AWS S3 厂商配置映射
     * <p>Key: 配置名称（如 default, backup, test 等）</p>
     * <p>Value: 对应的厂商连接配置</p>
     * <p>配置示例：
     * <pre>
     * storage.aws.default.region=us-east-1
     * storage.aws.default.access-key=AKIAxxxxxx
     * storage.aws.default.secret-key=xxxxxxxx
     * storage.aws.default.bucket=my-bucket
     * </pre>
     * </p>
     */
    private Map<String, VendorConfig> aws = new HashMap<>();

    /**
     * 获取当前激活的厂商配置
     * <p>根据 {@link #vendor} 字段的值，从对应的厂商配置映射中获取名为 "default" 的配置</p>
     * <p>如果找不到对应的配置，则返回一个空的 {@link VendorConfig} 实例（所有字段为 null 或默认值）</p>
     *
     * @return 当前激活的厂商配置对象
     * @throws IllegalArgumentException 如果 vendor 值不在支持列表中
     */
    public VendorConfig getActive() {
        return switch (vendor) {
            case "aliyun"  -> aliyun.getOrDefault("default", new VendorConfig());
            case "tencent" -> tencent.getOrDefault("default", new VendorConfig());
            case "aws"     -> aws.getOrDefault("default", new VendorConfig());
            default        -> minio.getOrDefault("default", new VendorConfig());
        };
    }

    /**
     * 厂商连接配置类
     * <p>用于配置具体的存储厂商连接参数</p>
     */
    @Data
    public static class VendorConfig {

        /**
         * 服务端点地址
         * <p>对于 MinIO：格式为 http://host:port，如 http://localhost:9000</p>
         * <p>对于阿里云 OSS：格式为 oss-cn-hangzhou.aliyuncs.com（不含协议头）</p>
         * <p>对于腾讯云 COS：格式为 cos.ap-guangzhou.myqcloud.com（不含协议头）</p>
         * <p>对于 AWS S3：格式为 s3.amazonaws.com（不含协议头）</p>
         * <p>注意：如果未配置，SDK 将使用默认端点</p>
         */
        private String endpoint;

        /**
         * 区域标识
         * <p>对于阿里云：如 cn-hangzhou, cn-beijing</p>
         * <p>对于腾讯云：如 ap-guangzhou, ap-shanghai</p>
         * <p>对于 AWS：如 us-east-1, ap-northeast-1</p>
         * <p>对于 MinIO：通常不需要配置，留空即可</p>
         * <p>注意：部分厂商（如腾讯云）此字段为必填</p>
         */
        private String region;

        /**
         * 访问密钥 ID / Access Key
         * <p>对于阿里云：AccessKey ID</p>
         * <p>对于腾讯云：SecretId</p>
         * <p>对于 AWS：Access Key ID</p>
         * <p>对于 MinIO：Access Key</p>
         * <p>注意：此字段为必填，请妥善保管，避免泄露</p>
         */
        private String accessKey;

        /**
         * 访问密钥 Secret / Secret Key
         * <p>对于阿里云：AccessKey Secret</p>
         * <p>对于腾讯云：SecretKey</p>
         * <p>对于 AWS：Secret Access Key</p>
         * <p>对于 MinIO：Secret Key</p>
         * <p>注意：此字段为必填，请妥善保管，避免泄露</p>
         */
        private String secretKey;

        /**
         * 默认存储桶名称
         * <p>如果业务代码中未指定桶名，将使用此默认桶</p>
         * <p>命名规则：</p>
         * <ul>
         *   <li>只能包含小写字母、数字和连字符（-）</li>
         *   <li>长度必须在 3-63 个字符之间</li>
         *   <li>对于腾讯云，格式为 bucket-name-appid（如 my-bucket-1234567890）</li>
         * </ul>
         */
        private String bucket;

        /**
         * 是否使用路径风格访问
         * <p>默认值：false（使用虚拟主机风格）</p>
         * <p>路径风格（Path-Style）：http://endpoint/bucket/key</p>
         * <p>虚拟主机风格（Virtual-Hosted-Style）：http://bucket.endpoint/key</p>
         * <p>注意：</p>
         * <ul>
         *   <li>MinIO 通常需要设置为 true（因为默认使用路径风格）</li>
         *   <li>阿里云、腾讯云、AWS 通常使用虚拟主机风格（默认 false）</li>
         *   <li>如果 endpoint 包含 bucket 信息，可能需要设置为 true</li>
         * </ul>
         */
        private boolean pathStyle = false;

        /**
         * CDN 加速域名
         * <p>如果配置了此字段，生成的下载 URL 将使用 CDN 域名而非源站地址</p>
         * <p>配置示例：https://cdn.example.com</p>
         * <p>注意：需要确保 CDN 域名已绑定到对应的存储桶</p>
         */
        private String cdnDomain;
    }
}
