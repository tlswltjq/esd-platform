package com.stove.download.core.domain;

import java.time.Instant;

/** 짧은 수명의 서명된 다운로드 주소. 어떤 CDN 을 쓰는지는 이 값에 드러나지 않는다. */
public record SignedUrl(String url, Instant expiresAt) {
}
