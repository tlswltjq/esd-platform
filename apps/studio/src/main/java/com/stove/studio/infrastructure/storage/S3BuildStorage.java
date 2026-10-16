package com.stove.studio.infrastructure.storage;

import java.time.Duration;

import com.stove.common.web.error.*;
import com.stove.studio.core.port.BuildStorage;

import jakarta.annotation.*;


import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.*;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.nio.file.*;
import java.util.UUID;

public class S3BuildStorage implements BuildStorage {
    private final String bucket;
    private final Duration presignTtl;
    private final boolean encryptionEnabled;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3BuildStorage(S3Client s3, S3Presigner presigner, String bucket, Duration presignTtl, boolean encryptionEnabled) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
        this.encryptionEnabled = encryptionEnabled;
    }

    @PostConstruct
    void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() != 404) throw failure;
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }

    @Override
    public String put(Long projectId, Path file, String extension, String type) {
        String key = "projects/" + projectId + "/" + UUID.randomUUID() + "." + extension;
        PutObjectRequest.Builder request =
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(type);
        if (encryptionEnabled)
            request.serverSideEncryption(ServerSideEncryption.AES256);
        try {
            s3.putObject(request.build(), RequestBody.fromFile(file));
        } catch (RuntimeException failure) {
            try {
                s3.deleteObject(
                        DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return "s3://" + bucket + "/" + key;
    }

    @Override
    public long size(String path) {
        return s3.headObject(
                        HeadObjectRequest.builder()
                                .bucket(bucket)
                                .key(key(path))
                                .build())
                .contentLength();
    }

    @Override
    public void delete(String path) {
        s3.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(key(path)).build());
    }

    @Override
    public String thumbnailDownload(Long projectId, String assetId) {
        if (assetId == null || !assetId.matches("[0-9a-f-]{36}\\.(png|jpg)"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "썸네일 ID가 올바르지 않습니다.");
        String key = "projects/" + projectId + "/" + assetId;
        try {
            size("s3://" + bucket + "/" + key);
        } catch (S3Exception missing) {
            if (missing.statusCode() != 404) throw missing;
            key = "store-assets/" + projectId + "/" + assetId;
        }
        try {
            size("s3://" + bucket + "/" + key);
        } catch (S3Exception missing) {
            if (missing.statusCode() != 404) throw missing;
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return presigner
                .presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(presignTtl)
                                .getObjectRequest(
                                        GetObjectRequest.builder()
                                                .bucket(bucket)
                                                .key(key)
                                                .build())
                                .build())
                .url()
                .toString();
    }

    private String key(String path) {
        String prefix = "s3://" + bucket + "/";
        if (!path.startsWith(prefix)) throw new IllegalArgumentException("Unexpected storage path");
        return path.substring(prefix.length());
    }
}
