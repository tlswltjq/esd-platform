package com.stove.catalog.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 전체 재색인의 페이지 크기와 스로틀.
 *
 * @param pageSize     한 트랜잭션에서 발행할 상품 수
 * @param pageInterval 페이지 사이 대기. 재색인이 Outbox 릴레이를 독점해 정상 상태 변경
 *                     이벤트를 뒤로 밀어내는 것을 막는다 — 릴레이는 전 서비스 공유 자원이다
 */
@ConfigurationProperties(prefix = "stove.catalog.reindex")
public record ReindexProperties(int pageSize, Duration pageInterval) {

    public ReindexProperties {
        pageSize = pageSize <= 0 ? 500 : pageSize;
        pageInterval = pageInterval == null ? Duration.ofMillis(200) : pageInterval;
    }
}
