package com.stove.archfixture.violating.core.service;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.ProjectReviewRequestedEvent;

public class ReceiveExternalInput {
    public void execute(EventEnvelope envelope, ProjectReviewRequestedEvent event) {}
}
