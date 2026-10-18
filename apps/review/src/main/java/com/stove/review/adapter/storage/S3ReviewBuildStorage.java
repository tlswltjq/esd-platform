package com.stove.review.adapter.storage;

import java.time.Duration;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.*;
import com.stove.review.core.domain.BuildDownload;
import com.stove.review.core.port.ReviewBuildStorage;

import jakarta.annotation.PreDestroy;


import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.*;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;


public class S3ReviewBuildStorage implements ReviewBuildStorage {
    private final String bucket;
    private final Duration presignTtl;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3ReviewBuildStorage(S3Client s3, S3Presigner presigner, String bucket, Duration presignTtl) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
    }

    @Override
    public BuildDownload download(ProjectSnapshot snapshot) {
        String prefix = "s3://" + bucket + "/";
        if (snapshot.storagePath() == null
                || !snapshot.storagePath().startsWith(prefix)
                || snapshot.storagePath().length() == prefix.length())
            throw new BusinessException(ErrorCode.CONFLICT, "심사 빌드의 저장 경로가 올바르지 않습니다.");
        String key = snapshot.storagePath().substring(prefix.length());
        try {
            long size =
                    s3.headObject(
                                    HeadObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(key)
                                            .build())
                            .contentLength();
            if (size != snapshot.fileSize())
                throw new BusinessException(ErrorCode.CONFLICT, "심사 빌드의 크기가 스냅샷과 다릅니다.");
            PresignedGetObjectRequest signed =
                    presigner.presignGetObject(
                            GetObjectPresignRequest.builder()
                                    .signatureDuration(presignTtl)
                                    .getObjectRequest(
                                            GetObjectRequest.builder()
                                                    .bucket(bucket)
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
