package com.stove.payment.core.service;

import com.stove.payment.core.domain.PgUnavailable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Enabled only alongside durable result lookup; supplier uncertainty is not a Kafka POST retry. */
@Slf4j @RequiredArgsConstructor
public class RequestFirstRefundCancellation {
    private final RequestRefundCancellation cancellation;

    public void execute(String attemptId) {
        try { cancellation.executeFirst(attemptId); }
        catch (PgUnavailable unknown) {
            // Preparation and the GET schedule are already committed; database failures still propagate.
            log.warn("환불 첫 송신/조회 결과 미확정: 저장된 일정으로 확인 attemptId={}, errorType={}",
                    attemptId, unknown.getClass().getSimpleName());
        }
    }
}
