package com.stove.archfixture.compliant.core.service;

import org.springframework.transaction.annotation.Transactional;

/** 준수 픽스처 — 규칙을 전부 지킨다. 규칙이 <b>아무거나 잡는 것은 아님</b>을 보이는 대조군. */
@Transactional
public class CompliantService {

    @Transactional
    public void doWork() {
    }

    public com.stove.common.kafka.contract.payload.ProjectReviewApprovedEvent publish(
            com.stove.archfixture.compliant.core.domain.InternalInput input) {
        return com.stove.common.kafka.contract.payload.ProjectReviewApprovedEvent.of(
                1L, 1L, 1, "game-1", "reviewer");
    }
}
