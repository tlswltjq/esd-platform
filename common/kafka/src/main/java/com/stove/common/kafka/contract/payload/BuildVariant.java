package com.stove.common.kafka.contract.payload;

/**
 *  One validated artifact in a submission or published release.
 *
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param platform 빌드가 실행될 대상 플랫폼.
 * @param architecture 빌드가 실행될 CPU 아키텍처.
 * @param version 배포 파일의 제품 버전 문자열.
 * @param fileSize 배포 파일의 크기(바이트).
 * @param checksum 파일 무결성 검증에 사용하는 체크섬.
 * @param storagePath 파일이 보관된 객체 저장소 경로.
 * @param deltaFromVersion 델타 패치를 적용할 기준 버전으로, 없으면 전체 파일.
 */
public record BuildVariant(
        Long buildId,
        String platform,
        String architecture,
        String version,
        long fileSize,
        String checksum,
        String storagePath,
        String deltaFromVersion
) {
    public boolean delta() {
        return deltaFromVersion != null;
    }
}
