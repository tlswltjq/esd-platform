package com.stove.review.core.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param autoApproveSelfRated 자체등급분류 건을 접수 즉시 자동 승인할지 여부.
 *                             운영에서는 내부 심사 담당자가 승인 API를 호출하는 흐름으로 끈다.
 */
@ConfigurationProperties(prefix = "stove.review")
public record ReviewProperties(Boolean autoApproveSelfRated, String defaultSelfRatingCode) {

    public ReviewProperties {
        autoApproveSelfRated = autoApproveSelfRated == null || autoApproveSelfRated;
        defaultSelfRatingCode = defaultSelfRatingCode == null ? "ALL" : defaultSelfRatingCode;
    }
}
