package com.stove.studio.core.domain.build;

import java.util.List;

/**
 * 객체 저장소의 분할 업로드 식별자와 조각별 전송 정보.
 *
 * @param storagePath 파일이 보관된 객체 저장소 경로.
 * @param storageUploadId 객체 저장소가 발급한 분할 업로드 식별자.
 * @param partSize 분할 업로드 조각 하나의 기준 크기(바이트).
 * @param parts 분할 업로드 조각별 업로드 URL 목록.
 */
public record MultipartUploadTicket(
        String storagePath,
        String storageUploadId,
        long partSize,
        List<UploadPartUrl> parts
) {
}
