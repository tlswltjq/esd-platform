package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundCancellationResumeRequest;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class RequestRefundCancellationResumeTest {
    private static final Instant BASE = Instant.parse("2026-10-28T14:00:00.123456Z");

    @ParameterizedTest @ValueSource(strings = {"uq_cancel_resume_request", "refund_cancellation_hold.uq_cancel_resume_request"})
    void onlyTheResumeCommandUniqueConstraintBecomesAConflict(String constraint) {
        Fixture fixture = fixture(constraint);
        assertThatThrownBy(() -> fixture.service().execute(fixture.request())).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test void unrelatedIntegrityFailuresArePreservedForDiagnosis() {
        Fixture fixture = fixture("ck_cancel_hold_resume");
        assertThatThrownBy(() -> fixture.service().execute(fixture.request())).isSameAs(fixture.failure());
    }

    private Fixture fixture(String constraint) {
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW", "pg-original",
                BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        RefundExecution execution = RefundExecution.receive(paid, execution(paid), BASE);
        execution.prepareCancellation(BASE); execution.freezeCancellationBody("frozen-body");
        RefundCancellationClaim claim = execution.getCancellationSchedule().claim(id(), BASE, Duration.ofSeconds(231), false);
        RefundCancellationHold hold = RefundCancellationHold.forbidden(execution.getAttemptId(), claim, BASE.plusSeconds(1), true);
        execution.holdCancellation(hold.getHoldId(), claim);
        PaymentRepository payments = mock(PaymentRepository.class); RefundExecutionRepository executions = mock(RefundExecutionRepository.class);
        RefundCancellationHoldRepository holds = mock(RefundCancellationHoldRepository.class);
        when(executions.findPaymentIdByAttemptId(execution.getAttemptId())).thenReturn(Optional.of(paid.getPaymentId()));
        when(payments.lockByPaymentId(paid.getPaymentId())).thenReturn(Optional.of(paid));
        when(executions.lockByAttemptId(execution.getAttemptId())).thenReturn(Optional.of(execution));
        when(holds.findByHoldId(hold.getHoldId())).thenReturn(Optional.of(hold));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("constraint violation",
                new ConstraintViolationException("constraint violation", new SQLException("fixture"), constraint));
        doThrow(failure).when(executions).flush();
        return new Fixture(new RequestRefundCancellationResume(payments, executions, holds, Clock.fixed(BASE.plusSeconds(10), ZoneOffset.UTC)),
                new RefundCancellationResumeRequest(id(), execution.getAttemptId(), hold.getHoldId(), "operator", "reviewed"), failure);
    }

    private record Fixture(RequestRefundCancellationResume service, RefundCancellationResumeRequest request, DataIntegrityViolationException failure) { }
}
