package com.stove.archfixture.violating.core.domain;

import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;

public record ExternalInput(ProjectReviewRequestedEvent event) {}
