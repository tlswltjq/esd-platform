package com.stove.studio.core.domain.build;

/**
 * 비동기 파일 검증에 필요한 빌드 상태와 선언값의 사본.
 *
 * @param status 검증 시작 시 확인한 빌드 처리 상태.
 * @param storagePath 파일이 보관된 객체 저장소 경로.
 * @param expectedChecksum 검증 시 실제 파일과 대조할 선언된 체크섬.
 * @param expectedSize 검증 시 실제 파일과 대조할 선언된 크기(바이트).
 * @param productVersion 빌드의 제품 버전 문자열.
 */
public record BuildValidationSnapshot(
        BuildStatus status,
        String storagePath,
        String expectedChecksum,
        long expectedSize,
        String productVersion
) {
}
