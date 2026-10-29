package com.stove.payment.core.port;

public interface ApprovalPayloadProtection {
    String seal(String approvalId, String body);
    String open(String approvalId, String protectedBody);
}
