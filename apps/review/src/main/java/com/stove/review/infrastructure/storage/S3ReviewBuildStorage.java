package com.stove.review.infrastructure.storage;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.*;
import com.stove.review.core.domain.BuildDownload;
import com.stove.review.core.port.ReviewBuildStorage;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.*;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;

@Component
public class S3ReviewBuildStorage implements ReviewBuildStorage {
    private final ReviewStorageProperties properties;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3ReviewBuildStorage(ReviewStorageProperties p) {
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

    @Override
    public BuildDownload download(ProjectSnapshot snapshot) {
        String prefix = "s3://" + properties.bucket() + "/";
        if (snapshot.storagePath() == null
                || !snapshot.storagePath().startsWith(prefix)
                || snapshot.storagePath().length() == prefix.length())
            throw new BusinessException(ErrorCode.CONFLICT, "심사 빌드의 저장 경로가 올바르지 않습니다.");
        String key = snapshot.storagePath().substring(prefix.length());
        try {
            long size =
                    s3.headObject(
                                    HeadObjectRequest.builder()
                                            .bucket(properties.bucket())
                                            .key(key)
                                            .build())
                            .contentLength();
            if (size != snapshot.fileSize())
                throw new BusinessException(ErrorCode.CONFLICT, "심사 빌드의 크기가 스냅샷과 다릅니다.");
            var signed =
                    presigner.presignGetObject(
                            GetObjectPresignRequest.builder()
                                    .signatureDuration(properties.presignTtl())
                                    .getObjectRequest(
                                            GetObjectRequest.builder()
                                                    .bucket(properties.bucket())
                                                    .key(key)
                                                    .build())
                                    .build());
            return new BuildDownload(
                    signed.url().toString(), signed.expiration(), size, snapshot.checksum());
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404)
                throw new BusinessException(ErrorCode.CONFLICT, "심사 빌드 파일을 찾을 수 없습니다.");
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        } catch (SdkClientException failure) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }
}
