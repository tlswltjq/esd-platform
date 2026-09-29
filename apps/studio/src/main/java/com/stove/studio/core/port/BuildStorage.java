package com.stove.studio.core.port;

import com.stove.studio.core.domain.MultipartUploadTicket;
import com.stove.studio.core.domain.StoredObjectInfo;
import com.stove.studio.core.domain.UploadTicket;
import com.stove.studio.core.domain.UploadedPart;
import com.stove.studio.core.domain.UploadPartUrl;
import java.nio.file.Path;
import java.util.List;

/**
 * 빌드 바이너리 저장 포트(S3 대체).
 * 스켈레톤에서는 경로만 발급하고, 실제 업로드는 클라이언트가 presigned URL 로 수행하는 형태를 가정한다.
 */
public interface BuildStorage {

    /** 업로드 대상 경로와 presigned URL 발급 */
    UploadTicket issueUploadTicket(String productCode, String version);

    MultipartUploadTicket beginMultipart(String productCode, String artifactKey, String fileName, long fileSize);

    List<UploadPartUrl> presignParts(String storagePath, String storageUploadId, int partCount);

    void completeMultipart(String storagePath, String storageUploadId, List<UploadedPart> parts);

    StoredObjectInfo head(String storagePath);

    void downloadTo(String storagePath, Path destination);

    String presignDownload(String storagePath);

    void abortMultipart(String storagePath, String storageUploadId);

    void delete(String storagePath);
}
