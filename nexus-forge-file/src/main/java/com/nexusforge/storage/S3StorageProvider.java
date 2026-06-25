package com.nexusforge.storage;

import com.nexusforge.config.StorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class S3StorageProvider implements StorageProvider {

    private final StorageProperties storageProps;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3StorageProvider(StorageProperties storageProps) {
        this.storageProps = storageProps;
        var p = storageProps.getActive();
        AwsBasicCredentials creds = AwsBasicCredentials.create(p.getAccessKey(), p.getSecretKey());

        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(p.isPathStyle())
                .build();

        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(p.getEndpoint()))
                .region(Region.of(p.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(s3Config)
                .build();

        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(p.getEndpoint()))
                .region(Region.of(p.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(s3Config)
                .build();
    }


    @Override
    public void createBucket(String bucketName) {
        s3.createBucket(b -> b.bucket(bucketName));
    }

    @Override
    public boolean bucketExists(String bucketName) {
        try {
            s3.headBucket(b -> b.bucket(bucketName));
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        }
    }

    @Override
    public void deleteBucket(String bucketName) {
        s3.deleteBucket(b -> b.bucket(bucketName));
    }

    @Override
    public String upload(String bucketName, String key, InputStream input, long contentLength, String contentType) {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        s3.putObject(req, RequestBody.fromInputStream(input, contentLength));
        return key;
    }

    @Override
    public InputStream download(String bucketName, String key) {
        return s3.getObject(b -> b.bucket(bucketName).key(key),
                ResponseTransformer.toInputStream());
    }

    @Override
    public void delete(String bucketName, String key) {
        s3.deleteObject(b -> b.bucket(bucketName).key(key));
    }

    @Override
    public void deleteBatch(String bucketName, List<String> keys) {
        // S3 批量删除最多 1000 个 key/批
        List<ObjectIdentifier> objects = keys.stream()
                .map(k -> ObjectIdentifier.builder().key(k).build())
                .collect(Collectors.toList());
        DeleteObjectsRequest req = DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete(d -> d.objects(objects).quiet(true))
                .build();
        s3.deleteObjects(req);
    }

    @Override
    public boolean exists(String bucketName, String key) {
        try {
            s3.headObject(b -> b.bucket(bucketName).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public String initiateMultipartUpload(String bucketName, String key, String contentType) {
        CreateMultipartUploadResponse resp = s3.createMultipartUpload(b -> b
                .bucket(bucketName)
                .key(key)
                .contentType(contentType));
        return resp.uploadId();
    }

    @Override
    public String uploadPart(String bucketName, String key, String uploadId, int partNumber, InputStream partData, long partSize) {
        UploadPartRequest req = UploadPartRequest.builder()
                .bucket(bucketName)
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(partSize)
                .build();
        UploadPartResponse resp = s3.uploadPart(req, RequestBody.fromInputStream(partData, partSize));
        return resp.eTag();
    }

    @Override
    public String completeMultipartUpload(String bucketName, String key, String uploadId, List<String> partETags) {
        List<CompletedPart> parts = new java.util.ArrayList<>();
        for (int i = 0; i < partETags.size(); i++) {
            parts.add(CompletedPart.builder()
                    .partNumber(i + 1)
                    .eTag(partETags.get(i))
                    .build());
        }
        CompleteMultipartUploadRequest req = CompleteMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                .build();
        CompleteMultipartUploadResponse resp = s3.completeMultipartUpload(req);
        return resp.location();
    }

    @Override
    public void abortMultipartUpload(String bucketName, String key, String uploadId) {
        s3.abortMultipartUpload(b -> b.bucket(bucketName).key(key).uploadId(uploadId));
    }

    @Override
    public String generatePresignedPutUrl(String bucketName, String key, Duration expiry) {
        PresignedPutObjectRequest req = presigner.presignPutObject(b -> b
                .signatureDuration(expiry)
                .putObjectRequest(p -> p.bucket(bucketName).key(key)));
        return req.url().toString();
    }

    @Override
    public String generatePresignedGetUrl(String bucketName, String key, Duration expiry) {
        PresignedGetObjectRequest req = presigner.presignGetObject(b -> b
                .signatureDuration(expiry)
                .getObjectRequest(p -> p.bucket(bucketName).key(key)));
        return req.url().toString();
    }

    @Override
    public String vendor() {
        return storageProps.getVendor();
    }
}
