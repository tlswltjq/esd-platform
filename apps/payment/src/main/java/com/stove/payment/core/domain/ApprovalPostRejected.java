package com.stove.payment.core.domain;

public class ApprovalPostRejected extends RuntimeException {
    public ApprovalPostRejected() { super("PG approval request requires investigation"); }
}
