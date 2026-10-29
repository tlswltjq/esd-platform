package com.stove.payment.adapter.pg;

import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.PgGateway;
import com.stove.payment.core.port.PgPaymentApprovalGateway;
import java.util.List;

public class AdmittedPortOnePgGateway implements PgGateway {
    private final PaymentApprovalRepository approvals;
    private final PgPaymentApprovalGateway gateway;
    public AdmittedPortOnePgGateway(PaymentApprovalRepository approvals,PgPaymentApprovalGateway gateway) {
        this.approvals=approvals; this.gateway=gateway;
    }
    @Override public PgResult charge(PgChargeRequest request) {
        PaymentApproval approval=approvals.findFirstByPaymentIdAndStatusInOrderByIdDesc(request.paymentId(),
                List.of(PaymentApprovalStatus.ADMITTED,PaymentApprovalStatus.REVIEW_REQUIRED,PaymentApprovalStatus.SUCCEEDED))
                .orElseThrow(()->new PgUnavailable("Order admission is required before confirming a payment"));
        if (approval.getAdmittedAt()==null || !approval.purchase().purchase().equals(request))
            throw new IllegalArgumentException("Approval does not identify this purchase");
        ApprovalObservation observation=gateway.inspect(approval.purchase());
        if (observation.state()!=ApprovalObservation.State.PAID) throw new PgUnavailable("Admitted payment is not confirmed paid");
        return observation.paid();
    }
}
