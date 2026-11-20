package com.stove.archfixture.violating.core.service;

import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.payload.ProjectReviewRequestedEvent;

public class ReceiveExternalInput {
    public void execute(EventEnvelope envelope, ProjectReviewRequestedEvent event) {}
}
