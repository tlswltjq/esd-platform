package com.stove.payment.core.domain;

import java.time.Instant;

public record RefundInvestigationView(String requestId, String attemptId, String operatorSubject, String note,
        Instant recordedAt, Instant delayedSince) { }
