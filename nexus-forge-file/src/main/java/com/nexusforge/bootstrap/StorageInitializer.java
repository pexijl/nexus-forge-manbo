package com.nexusforge.bootstrap;

import com.nexusforge.config.StorageProperties;
import com.nexusforge.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.auto-create-bucket", havingValue = "true", matchIfMissing = true)
public class StorageInitializer implements ApplicationRunner {

    private final StorageProvider storage;
    private final StorageProperties props;

    @Override
    public void run(ApplicationArguments args) {
        var cfg = props.getActive();
        String bucket = cfg.getBucket();
        String vendor = storage.vendor();

        if (bucket == null || bucket.isBlank()) {
            log.warn("存储桶未配置，跳过自动创建");
            return;
        }

        log.debug("检查存储桶是否存在: bucket={}, vendor={}", bucket, vendor);

        if (storage.bucketExists(bucket)) {
            log.info("存储桶已存在: bucket={}, vendor={}", bucket, vendor);
            return;
        }

        try {
            storage.createBucket(bucket);
            log.info("存储桶创建成功: bucket={}, vendor={}", bucket, vendor);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            log.info("存储桶已被其他实例创建: bucket={}, vendor={}", bucket, vendor);
        } catch (SdkException e) {
            log.error("存储桶创建失败: bucket={}, vendor={}, 错误={}",
                    bucket, vendor, e.getMessage(), e);
            // 不抛，避免阻塞启动 —— 由运维决定后续
        } catch (Exception e) {
            log.error("创建存储桶时发生未知异常: bucket={}, vendor={}",
                    bucket, vendor, e);
        }
    }
}
