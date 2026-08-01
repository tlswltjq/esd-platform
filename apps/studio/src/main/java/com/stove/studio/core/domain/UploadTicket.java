package com.stove.studio.core.domain;

/** 빌드 업로드 대상 경로와 presigned URL. 저장소가 S3 인지 여부는 이 값에 드러나지 않는다. */
public record UploadTicket(String storagePath, String uploadUrl) {
}
