package com.stove.studio.core.domain.access;

import com.stove.studio.core.domain.release.ReleaseChannel;
import java.time.Instant;

/**
 * 내부 테스터에게 발급하는 채널별 빌드 설치 정보.
 *
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param channel 릴리스를 공개하거나 설치를 허용할 배포 채널.
 * @param downloadUrl 파일을 내려받을 수 있는 서명된 URL.
 * @param expiresAt 권한 또는 URL의 유효 기간이 끝나는 시각.
 */
public record TesterInstallation(Long buildId, ReleaseChannel channel,
                                 String downloadUrl, Instant expiresAt) {
}
