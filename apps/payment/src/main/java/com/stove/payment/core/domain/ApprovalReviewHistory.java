package com.stove.payment.core.domain;

import org.springframework.data.domain.Page;

/** Investigation notes of a held approval and, once it was closed, its closure record. */
public record ApprovalReviewHistory(Page<ApprovalInvestigationView> investigations, ApprovalClosureView closure) { }
