package com.stove.studio.core.domain.build;

/**
 *  빌드 업로드 대상 경로와 presigned URL. 저장소가 S3 인지 여부는 이 값에 드러나지 않는다.
 *
 * @param storagePath 파일이 보관된 객체 저장소 경로.
 * @param uploadUrl 파일 또는 업로드 조각을 전송할 서명된 URL.
 */
public record UploadTicket(String storagePath, String uploadUrl) {
}
