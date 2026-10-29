package com.stove.payment.core.port;

import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalPurchase;
import com.stove.payment.core.domain.ApprovalToken;
import java.time.Instant;

public interface PgPaymentApprovalGateway {
    String prepareBody(ApprovalPurchase purchase, ApprovalToken token);
    ApprovalObservation inspect(ApprovalPurchase purchase);
    void submit(String approvalId, ApprovalPurchase purchase, String fixedBody, Instant preparedAt, Instant leaseUntil);
}
