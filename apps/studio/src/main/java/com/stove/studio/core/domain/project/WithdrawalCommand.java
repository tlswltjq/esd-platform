package com.stove.studio.core.domain.project;

public record WithdrawalCommand(String requestId, String reason) {
    public WithdrawalCommand {
        ProjectCommandValues.requireRequestKey(requestId);
        ProjectCommandValues.requireReason(reason);
        if (reason != null) {
            reason = reason.strip();
            if (reason.isEmpty()) reason = null;
        }
    }
}
