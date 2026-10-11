package com.stove.studio.core.domain.build;

/**
 * CI 출처와 파일 선언값을 포함한 업로드 세션 생성 입력.
 *
 * @param productVersion 빌드의 제품 버전 문자열.
 * @param buildNumber 같은 제품 버전 내 빌드를 구별하는 번호.
 * @param platform 빌드가 실행될 대상 플랫폼.
 * @param architecture 빌드가 실행될 CPU 아키텍처.
 * @param fileName 업로드할 원본 파일 이름.
 * @param fileSize 배포 파일의 크기(바이트).
 * @param sha256 업로드 파일에 대해 선언한 SHA-256 체크섬.
 * @param commitSha 빌드에 사용한 소스 커밋 식별자.
 * @param repository 빌드를 생성한 소스 저장소.
 * @param sourceRef 빌드에 사용한 브랜치 또는 태그 참조.
 * @param ciProvider 빌드를 실행한 CI 제공자.
 * @param ciRunId CI 빌드 실행을 추적할 식별자.
 * @param idempotencyKey 같은 요청의 중복 처리를 방지하는 키.
 * @param deltaFromVersion 델타 패치를 적용할 기준 버전으로, 없으면 전체 파일.
 */
public record NewUploadSession(
        String productVersion,
        String buildNumber,
        String platform,
        String architecture,
        String fileName,
        long fileSize,
        String sha256,
        String commitSha,
        String repository,
        String sourceRef,
        String ciProvider,
        String ciRunId,
        String idempotencyKey,
        String deltaFromVersion
) {
    public NewUploadSession(String productVersion, String buildNumber, String platform,
                            String architecture, String fileName, long fileSize, String sha256,
                            String commitSha, String repository, String sourceRef,
                            String ciProvider, String ciRunId, String idempotencyKey) {
        this(productVersion, buildNumber, platform, architecture, fileName, fileSize, sha256,
                commitSha, repository, sourceRef, ciProvider, ciRunId, idempotencyKey, null);
    }
}
