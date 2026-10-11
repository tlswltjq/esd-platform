package com.stove.download.core.domain;

import com.stove.common.event.payload.BuildVariant;
import java.time.Instant;

/**
 *  소유 판정을 통과한 회원에게 발급되는 다운로드 티켓.
 *
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param releaseId 대상 릴리스 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param version 배포 파일의 제품 버전 문자열.
 * @param fileSize 배포 파일의 크기(바이트).
 * @param checksum 파일 무결성 검증에 사용하는 체크섬.
 * @param downloadUrl 파일을 내려받을 수 있는 서명된 URL.
 * @param expiresAt 권한 또는 URL의 유효 기간이 끝나는 시각.
 * @param platform 빌드가 실행될 대상 플랫폼.
 * @param architecture 빌드가 실행될 CPU 아키텍처.
 * @param deltaFromVersion 델타 패치를 적용할 기준 버전으로, 없으면 전체 파일.
 */
public record DownloadTicket(
        String productCode,
        Long releaseId,
        Long buildId,
        String version,
        long fileSize,
        String checksum,
        String downloadUrl,
        Instant expiresAt,
        String platform,
        String architecture,
        String deltaFromVersion
) {
    public DownloadTicket(String productCode, Long releaseId, Long buildId, String version,
                          long fileSize, String checksum, String downloadUrl, Instant expiresAt) {
        this(productCode, releaseId, buildId, version, fileSize, checksum,
                downloadUrl, expiresAt, null, null, null);
    }

    public static DownloadTicket of(PatchManifest manifest, SignedUrl signed) {
        return new DownloadTicket(
                manifest.getProductCode(),
                manifest.getReleaseId(),
                manifest.getBuildId(),
                manifest.getVersion(),
                manifest.getFileSize(),
                manifest.getChecksum(),
                signed.url(),
                signed.expiresAt(), null, null, null);
    }

    public static DownloadTicket of(PatchManifest manifest, BuildVariant variant, SignedUrl signed) {
        return new DownloadTicket(manifest.getProductCode(), manifest.getReleaseId(),
                variant.buildId(), variant.version(), variant.fileSize(), variant.checksum(),
                signed.url(), signed.expiresAt(), variant.platform(), variant.architecture(),
                variant.deltaFromVersion());
    }
}
