package com.stove.studio.core.domain.project;

public record SubmissionCommand(String requestId, long contentVersion) {
    public SubmissionCommand {
        ProjectCommandValues.requireRequestKey(requestId);
        ProjectCommandValues.requireContentVersion(contentVersion);
    }
}
