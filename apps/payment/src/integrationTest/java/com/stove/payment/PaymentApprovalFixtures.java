package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;

import com.stove.payment.core.domain.ApprovalToken;
import com.stove.payment.core.domain.OrderAdmissionReceipt;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalInput;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import java.time.Duration;
import java.time.Instant;

final class PaymentApprovalFixtures {
    private PaymentApprovalFixtures() { }
    // Synthetic Order receipt for webhook/history tests; the complete HTTP admission is tested separately.
    static void admit(PaymentApprovalRepository approvals, Payment payment) {
        String attempt = "success-" + payment.getPaymentId();
        if (approvals.findBySupplierAttemptId(attempt).isPresent()) return;
        Instant at = payment.getOrderOccurredAt();
        PaymentApprovalInput input = new PaymentApprovalInput(id(),payment.getOrderNo(),attempt,new ApprovalToken("fixture-token"));
        PaymentApproval approval = PaymentApproval.receive(payment,input,"fixture-encrypted-payload",at);
        approval.admit(new OrderAdmissionReceipt(input.requestId(),payment.getOrderNo(),payment.getPaymentId(),at,at.plus(Duration.ofMinutes(30))));
        approvals.saveAndFlush(approval);
    }
}
