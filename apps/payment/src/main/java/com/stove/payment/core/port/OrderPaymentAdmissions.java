package com.stove.payment.core.port;

import com.stove.payment.core.domain.OrderAdmissionFailure;
import com.stove.payment.core.domain.OrderAdmissionFailureReceipt;
import com.stove.payment.core.domain.OrderAdmissionReceipt;
import com.stove.payment.core.domain.OrderAdmissionRequest;
import java.util.Optional;

public interface OrderPaymentAdmissions {
    Optional<OrderAdmissionReceipt> admit(OrderAdmissionRequest request);
    OrderAdmissionFailureReceipt fail(OrderAdmissionFailure request);
}
