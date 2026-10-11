package com.stove.studio.core.domain.build;

import java.time.Instant;
import java.util.List;

/**
 * 분할 업로드 세션 생성 후 클라이언트에 전달할 정보.
 *
 * @param sessionId 생성한 업로드 세션 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param partSize 분할 업로드 조각 하나의 기준 크기(바이트).
 * @param parts 분할 업로드 조각별 업로드 URL 목록.
 * @param expiresAt 생성한 업로드 세션의 만료 시각.
 */
public record CreatedUploadSession(
        Long sessionId,
        Long buildId,
        long partSize,
        List<UploadPartUrl> parts,
        Instant expiresAt
) {
}
