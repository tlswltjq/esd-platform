package com.stove.archfixture.violating.core.service;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.ProjectReviewRequestedEvent;

public class ReceiveExternalInput {
    public void execute(EventMessage message, ProjectReviewRequestedEvent event) {}
}
