package com.stove.studio.infrastructure.storage;

import com.stove.studio.core.domain.MultipartUploadTicket;
import com.stove.studio.core.domain.StoredObjectInfo;
import com.stove.studio.core.domain.UploadPartUrl;
import com.stove.studio.core.domain.UploadTicket;
import com.stove.studio.core.domain.UploadedPart;
import com.stove.studio.core.port.BuildStorage;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 로컬용 스텁. 운영에서는 S3 presigned URL 발급 구현으로 교체된다. */
@Slf4j
@Profile("!prod")
@Component
@ConditionalOnProperty(name = "stove.storage.provider", havingValue = "mock", matchIfMissing = true)
public class MockBuildStorage implements BuildStorage {

    private static final String BUCKET = "s3://stove-builds";

    @Override
    public UploadTicket issueUploadTicket(String productCode, String version) {
        String path = "%s/%s/%s/game.pak".formatted(BUCKET, productCode, version);
        log.info("[MOCK S3] 업로드 경로 발급 {}", path);
        return new UploadTicket(path, "https://mock-s3.local/upload?path=" + path);
    }

    @Override
    public MultipartUploadTicket beginMultipart(String productCode, String artifactKey,
                                                String fileName, long fileSize) {
        String path = "%s/%s/artifacts/%s/%s".formatted(BUCKET, productCode, artifactKey, fileName);
        return new MultipartUploadTicket(path, "mock-" + artifactKey, fileSize,
                List.of(new UploadPartUrl(1, "https://mock-s3.local/multipart/" + artifactKey + "/1")));
    }

    @Override
    public List<UploadPartUrl> presignParts(String storagePath, String storageUploadId, int partCount) {
        return java.util.stream.IntStream.rangeClosed(1, partCount)
                .mapToObj(part -> new UploadPartUrl(part,
                        "https://mock-s3.local/multipart/" + storageUploadId + "/" + part))
                .toList();
    }

    @Override
    public void completeMultipart(String storagePath, String storageUploadId, List<UploadedPart> parts) {
        log.info("[MOCK S3] multipart 완료 {}", storagePath);
    }

    @Override
    public StoredObjectInfo head(String storagePath) {
        return new StoredObjectInfo(0, "mock");
    }

    @Override
    public void downloadTo(String storagePath, Path destination) {
        throw new UnsupportedOperationException("mock storage has no object content");
    }

    @Override
    public void abortMultipart(String storagePath, String storageUploadId) {
        log.info("[MOCK S3] multipart 중단 {}", storagePath);
    }

    @Override
    public void delete(String storagePath) {
        log.info("[MOCK S3] 객체 삭제 {}", storagePath);
    }
}
