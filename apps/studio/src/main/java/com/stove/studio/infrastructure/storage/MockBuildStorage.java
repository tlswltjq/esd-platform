package com.stove.studio.infrastructure.storage;

import com.stove.studio.core.domain.UploadTicket;
import com.stove.studio.core.port.BuildStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 로컬용 스텁. 운영에서는 S3 presigned URL 발급 구현으로 교체된다. */
@Slf4j
@Profile("!prod")
@Component
public class MockBuildStorage implements BuildStorage {

    private static final String BUCKET = "s3://stove-builds";

    @Override
    public UploadTicket issueUploadTicket(String productCode, String version) {
        String path = "%s/%s/%s/game.pak".formatted(BUCKET, productCode, version);
        log.info("[MOCK S3] 업로드 경로 발급 {}", path);
        return new UploadTicket(path, "https://mock-s3.local/upload?path=" + path);
    }
}
