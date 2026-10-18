package com.stove.download.adapter.storage;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.download.core.domain.PublishedBuild;
import com.stove.download.core.domain.SignedUrl;
import com.stove.download.core.port.BuildStorage;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

public class S3BuildStorage implements BuildStorage {
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    public S3BuildStorage(S3Client client, S3Presigner presigner, String bucket, Duration ttl) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.ttl = ttl;
    }

    @Override
    public SignedUrl download(PublishedBuild build) {
        String prefix = "s3://" + bucket + "/";
        if (!build.storagePath().startsWith(prefix) || build.storagePath().length() == prefix.length())
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "공개 빌드 저장 참조가 올바르지 않습니다.");
        String key = build.storagePath().substring(prefix.length());
        try {
            long size = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
            if (size != build.fileSize())
                throw new BusinessException(ErrorCode.CONFLICT, "공개 빌드 파일 크기가 일치하지 않습니다.");
            PresignedGetObjectRequest signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()).build());
            return new SignedUrl(signed.url().toString(), signed.expiration());
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404)
                throw new BusinessException(ErrorCode.CONFLICT, "공개 빌드 파일을 찾을 수 없습니다.");
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        } catch (SdkClientException failure) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    @PreDestroy
    public void close() {
        presigner.close();
        client.close();
    }
}
