package com.stove.archfixture.violating.core.domain;

import com.stove.common.contract.payload.ProjectReviewRequestedEvent;

public record ExternalInput(ProjectReviewRequestedEvent event) {}
