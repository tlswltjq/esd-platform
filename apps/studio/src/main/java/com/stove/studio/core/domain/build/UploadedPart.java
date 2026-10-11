package com.stove.studio.core.domain.build;

/**
 * 분할 업로드 완료 요청에서 전달하는 조각 확인 값.
 *
 * @param partNumber 1부터 시작하는 분할 업로드 조각 번호.
 * @param etag 업로드 조각 완료 확인에 사용하는 객체 저장소 ETag.
 */
public record UploadedPart(int partNumber, String etag) {
}
