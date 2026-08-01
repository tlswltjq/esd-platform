package com.stove.studio.infrastructure.storage;

/**
 * 빌드 바이너리 저장 포트(S3 대체).
 * 스켈레톤에서는 경로만 발급하고, 실제 업로드는 클라이언트가 presigned URL 로 수행하는 형태를 가정한다.
 */
public interface BuildStorage {

    /** 업로드 대상 경로와 presigned URL 발급 */
    UploadTicket issueUploadTicket(String productCode, String version);

    record UploadTicket(String storagePath, String uploadUrl) {
    }
}
