package com.stove.studio.infrastructure.storage;

import com.stove.common.web.error.*;
import com.stove.studio.core.port.BuildStorage;

import jakarta.annotation.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.*;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.nio.file.*;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "stove.storage.provider", havingValue = "s3", matchIfMissing = true)
public class S3BuildStorage implements BuildStorage {
    private final ObjectStorageProperties properties;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3BuildStorage(ObjectStorageProperties p) {
        properties = p;
        var credentials =
                StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
        var config =
                S3Configuration.builder().pathStyleAccessEnabled(p.pathStyleRequired()).build();
        var client =
                S3Client.builder()
                        .region(Region.of(p.region()))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config);
        var signer =
                S3Presigner.builder()
                        .region(Region.of(p.region()))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config);
        if (p.pathStyleRequired()) {
            client.endpointOverride(URI.create(p.endpoint()));
            signer.endpointOverride(
                    URI.create(
                            p.presignEndpoint() == null || p.presignEndpoint().isBlank()
                                    ? p.endpoint()
                                    : p.presignEndpoint()));
        }
        s3 = client.build();
        presigner = signer.build();
    }

    @PostConstruct
    void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() != 404) throw failure;
            s3.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
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
        var request =
                PutObjectRequest.builder().bucket(properties.bucket()).key(key).contentType(type);
        if (properties.encryptionEnabled())
            request.serverSideEncryption(ServerSideEncryption.AES256);
        try {
            s3.putObject(request.build(), RequestBody.fromFile(file));
        } catch (RuntimeException failure) {
            try {
                s3.deleteObject(
                        DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return "s3://" + properties.bucket() + "/" + key;
    }

    @Override
    public long size(String path) {
        return s3.headObject(
                        HeadObjectRequest.builder()
                                .bucket(properties.bucket())
                                .key(key(path))
                                .build())
                .contentLength();
    }

    @Override
    public void delete(String path) {
        s3.deleteObject(
                DeleteObjectRequest.builder().bucket(properties.bucket()).key(key(path)).build());
    }

    @Override
    public String thumbnailDownload(Long projectId, String assetId) {
        if (assetId == null || !assetId.matches("[0-9a-f-]{36}\\.(png|jpg)"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "썸네일 ID가 올바르지 않습니다.");
        String key = "projects/" + projectId + "/" + assetId;
        try {
            size("s3://" + properties.bucket() + "/" + key);
        } catch (S3Exception missing) {
            if (missing.statusCode() != 404) throw missing;
            key = "store-assets/" + projectId + "/" + assetId;
        }
        try {
            size("s3://" + properties.bucket() + "/" + key);
        } catch (S3Exception missing) {
            if (missing.statusCode() != 404) throw missing;
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return presigner
                .presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(properties.presignTtl())
                                .getObjectRequest(
                                        GetObjectRequest.builder()
                                                .bucket(properties.bucket())
                                                .key(key)
                                                .build())
                                .build())
                .url()
                .toString();
    }

    private String key(String path) {
        String prefix = "s3://" + properties.bucket() + "/";
        if (!path.startsWith(prefix)) throw new IllegalArgumentException("Unexpected storage path");
        return path.substring(prefix.length());
    }
}
