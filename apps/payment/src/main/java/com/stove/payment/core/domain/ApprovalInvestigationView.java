package com.stove.payment.core.domain;

import java.time.Instant;

public record ApprovalInvestigationView(String requestId, String approvalId, String operatorSubject, String note,
        Instant recordedAt, Instant reviewStartedAt) { }
