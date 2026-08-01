package com.stove.download.core.port;

import com.stove.download.core.domain.SignedUrl;

/**
 * 저장소 경로를 회원별 서명 URL 로 바꾸는 아웃바운드 포트.
 *
 * <p>core 는 "만료 시각이 있는 서명된 주소가 필요하다"까지만 알고,
 * 서명 알고리즘과 CDN 주소 체계는 어댑터가 가진다.
 */
public interface DownloadUrlSigner {

    SignedUrl sign(String storagePath, Long memberId);
}
