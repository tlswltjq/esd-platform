package com.stove.payment.core.port;

import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.RefundCancellationDispatch;
import com.stove.payment.core.domain.RefundCancellationPermission;
import com.stove.payment.core.domain.RefundExecutionRequest;

/** The body is an opaque, persisted wire snapshot; secrets are never part of it. */
public interface RefundCancellationGateway {
    String prepareBody(RefundExecutionRequest request);
    RefundCancellationDispatch send(RefundCancellationPermission permission, PgRefundLookupRequest purchase, String body);
}
