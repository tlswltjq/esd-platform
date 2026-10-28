package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Durable receipt of an Order-authorized execution; receiving it does not call the supplier. */
@Entity @Table(name = "refund_execution")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String attemptId;
    @Column(nullable = false, updatable = false, length = 36)
    private String refundId;
    @Column(unique = true, updatable = false, length = 36)
    private String previousAttemptId;
    @Column(nullable = false, unique = true, updatable = false, length = 64)
    private String requestEventId;
    @Column(nullable = false, updatable = false)
    private Instant requestOccurredAt;
    @Column(nullable = false, updatable = false, length = 36)
    private String orderNo;
    @Column(nullable = false, updatable = false, length = 64)
    private String paymentId;
    @Column(nullable = false, updatable = false, length = 100)
    private String buyerSubject;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Column(nullable = false, updatable = false, length = 64)
    private String licenseSuspensionEventId;
    @Column(nullable = false, updatable = false)
    private Instant receivedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RefundExecutionStatus status;
    @Column(unique=true,length=36) private String preparationEventId;
    public boolean recordPreparationPublication() {
        if (firstCancellationPreparedAt==null) throw new IllegalStateException("Preparation time must be committed with its fact");
        if (preparationEventId!=null) return false;
        preparationEventId=UUID.randomUUID().toString(); return true;
    }

    @Column(name = "first_cancel_prepared_at")
    private Instant firstCancellationPreparedAt;
    @Column(name = "auto_cancel_stopped_at")
    private Instant automaticCancellationStoppedAt;
    private Instant cancellationBalanceChangedAt;
    @Column(length = 4096)
    private String cancellationRequestBody;
    @Embedded
    private RefundLookupSchedule lookupSchedule = new RefundLookupSchedule();
    @Embedded
    private RefundCancellationSchedule cancellationSchedule = new RefundCancellationSchedule();
    @Column(name = "active_cancel_hold_id", unique = true, length = 36)
    private String activeCancellationHoldId;

    public void holdCancellation(String holdId, RefundCancellationClaim claim) {
        if (!PaymentValues.uuid(holdId)) throw new IllegalArgumentException("A rejection hold identity is required");
        if (status != RefundExecutionStatus.PENDING || refundConfirmedAt != null || firstCancellationPreparedAt == null
                || cancellationRequestBody == null || activeCancellationHoldId != null || !cancellationSchedule.ownsClaim(claim))
            throw new BusinessException(ErrorCode.CONFLICT, "현재 송신의 거절 근거만 중단에 반영할 수 있습니다.");
        activeCancellationHoldId = holdId; cancellationSchedule.stop();
    }

    public void resumeCancellation(String holdId, Instant at) {
        if (!PaymentValues.uuid(holdId) || !PaymentValues.microsecond(at)) throw new IllegalArgumentException("A hold and precise resume time are required");
        if (!canResumeCancellation(holdId, at))
            throw new BusinessException(ErrorCode.CONFLICT, "현재 중단과 원래 송신 조건 안에서만 재개할 수 있습니다.");
        cancellationSchedule.resume(at); activeCancellationHoldId = null;
    }

    public boolean isCurrentCancellationHold(String holdId) {
        return holdId != null && holdId.equals(activeCancellationHoldId)
                && status == RefundExecutionStatus.PENDING && refundConfirmedAt == null;
    }

    public boolean canResumeCancellation(String holdId, Instant at) {
        return PaymentValues.uuid(holdId) && PaymentValues.microsecond(at) && isCurrentCancellationHold(holdId)
                && firstCancellationPreparedAt != null && cancellationRequestBody != null && automaticCancellationStoppedAt == null
                && cancellationBalanceChangedAt == null && receiptCancellationId == null && !at.isBefore(firstCancellationPreparedAt)
                && at.isBefore(cancellationResumeDeadline());
    }

    public Instant cancellationResumeDeadline() {
        return firstCancellationPreparedAt == null ? null : RefundCancellationWindow.deadline(firstCancellationPreparedAt);
    }

    @Column(length = 100) private String receiptCancellationId;
    @Enumerated(EnumType.STRING) @Column(length = 20) private PgRefundCancellationReceipt.State receiptState;
    private Instant receiptRequestedAt;
    private Instant receiptObservedAt;
    private Long receiptTaxFreeAmount;
    private Long receiptVatAmount;
    @Column(length = 500) private String receiptReason;
    @Column(unique = true, length = 64) private String failureEventId;
    private Instant failureConfirmedAt;
    @Column(length = 64) private String failureLookupHash;

    public PgRefundCancellationReceipt cancellationReceipt(Payment payment) {
        if (receiptCancellationId == null) return null;
        return new PgRefundCancellationReceipt(new PgRefundLookupRequest(paymentId, orderNo, amount, currency,
                payment.getProviderTransactionId(), payment.getProviderOccurredAt()), attemptId, receiptCancellationId,
                receiptState, receiptRequestedAt, receiptTaxFreeAmount, receiptVatAmount, receiptReason);
    }

    public boolean recordCancellationReceipt(Payment payment, PgRefundCancellationReceipt receipt, Instant at) {
        requireReceipt(payment, receipt, at);
        PgRefundCancellationReceipt previous = cancellationReceipt(payment);
        if (previous != null && !previous.failed().equals(receipt.failed()))
            throw new BusinessException(ErrorCode.CONFLICT, "취소 응답의 연결 근거가 다릅니다.");
        if (refundConfirmedAt != null || status == RefundExecutionStatus.FAILED) return false;
        if (previous != null && (previous.state() == receipt.state() || previous.state() == PgRefundCancellationReceipt.State.FAILED)) return false;
        receiptCancellationId = receipt.cancellationId(); receiptState = receipt.state();
        receiptRequestedAt = receipt.requestedAt(); receiptObservedAt = at;
        receiptTaxFreeAmount = receipt.taxFreeAmount(); receiptVatAmount = receipt.vatAmount(); receiptReason = receipt.reason();
        cancellationSchedule.stop();
        return true;
    }

    private void requireReceipt(Payment payment, PgRefundCancellationReceipt receipt, Instant at) {
        if (payment == null || receipt == null || !PaymentValues.microsecond(at) || at.isBefore(receivedAt)
                || firstCancellationPreparedAt == null || cancellationRequestBody == null || receipt.requestedAt().isAfter(at)
                || (receiptObservedAt != null && at.isBefore(receiptObservedAt)))
            throw new IllegalArgumentException("Prepared request and observed supplier receipt are required");
        payment.requireRefundRequest(request());
        if (!"PORTONE_TEST".equals(payment.getProvider()) || !attemptId.equals(receipt.attemptId())
                || !receipt.purchase().equals(new PgRefundLookupRequest(paymentId, orderNo, amount, currency,
                    payment.getProviderTransactionId(), payment.getProviderOccurredAt())))
            throw new IllegalArgumentException("Cancellation receipt belongs to another execution");
    }

    public boolean confirmFailure(Payment payment, PgRefundFailureConfirmation proof, String eventId, Instant at) {
        if (proof == null || !PaymentValues.text(eventId, 64)) throw new IllegalArgumentException("Failure proof is required");
        requireReceipt(payment, proof.failedReceipt(), at);
        PgRefundCancellationReceipt previous = cancellationReceipt(payment);
        if (previous == null) throw new IllegalArgumentException("No supplier cancellation is linked to this attempt");
        if (!previous.failed().equals(proof.failedReceipt())) throw new BusinessException(ErrorCode.CONFLICT, "확인한 실패가 원래 취소 요청과 다릅니다.");
        if (status == RefundExecutionStatus.FAILED) return false;
        if (status != RefundExecutionStatus.PENDING || refundConfirmedAt != null)
            throw new BusinessException(ErrorCode.CONFLICT, "환불 완료와 실패 근거가 충돌합니다.");
        recordCancellationReceipt(payment, proof.failedReceipt(), at);
        status = RefundExecutionStatus.FAILED; failureEventId = eventId; failureConfirmedAt = at; failureLookupHash = proof.snapshotHash();
        lookupSchedule.stop();
        cancellationSchedule.stop();
        return true;
    }

    public String freezeCancellationBody(String body) {
        if (firstCancellationPreparedAt == null || refundConfirmedAt != null || !PaymentValues.text(body, 4096))
            throw new IllegalArgumentException("A prepared execution and bounded wire body are required");
        if (cancellationRequestBody == null) cancellationRequestBody = body;
        return cancellationRequestBody;
    }

    public void blockAutomaticCancellation(Instant at) {
        if (firstCancellationPreparedAt == null || !PaymentValues.microsecond(at) || at.isBefore(firstCancellationPreparedAt))
            throw new IllegalArgumentException("A balance observation must follow dispatch preparation");
        if (cancellationBalanceChangedAt == null) cancellationBalanceChangedAt = at;
        cancellationSchedule.stop();
    }
    private Instant refundConfirmedAt;
    private Instant providerRefundedAt;
    @Column(unique = true, length = 64)
    private String refundConfirmationEventId;
    @Column(length = 30)
    private String refundConfirmationSource;
    @Column(unique = true, length = 64)
    private String cancellationSuccessEventId;
    private Instant cancellationSucceededAt;
    @Column(length = 100)
    private String successfulCancellationId;
    private Instant cancellationRequestedAt;
    private Instant responseCancelledAt;
    private Long responseTaxFreeAmount;
    private Long responseVatAmount;
    @Column(length = 500)
    private String responseReason;

    public boolean confirmCancellationSuccess(Payment payment, PgRefundCancellationSuccess success, String eventId, Instant at) {
        if (payment == null || success == null || !PaymentValues.text(eventId, 64) || !PaymentValues.microsecond(at)
                || firstCancellationPreparedAt == null || cancellationRequestBody == null || at.isBefore(receivedAt)
                || success.requestedAt().isAfter(at) || (success.cancellation().getCancelledAt() != null
                    && success.cancellation().getCancelledAt().isAfter(at))) throw new IllegalArgumentException("Invalid success observation");
        if (status == RefundExecutionStatus.FAILED) throw new BusinessException(ErrorCode.CONFLICT, "확정 실패와 성공 근거가 충돌합니다.");
        payment.requireRefundRequest(request());
        if (!"PORTONE_TEST".equals(payment.getProvider()) || !attemptId.equals(success.attemptId())
                || !success.purchase().equals(new PgRefundLookupRequest(paymentId, orderNo, amount, currency,
                    payment.getProviderTransactionId(), payment.getProviderOccurredAt())))
            throw new IllegalArgumentException("Success does not belong to this execution");
        PgRefundCancellationReceipt receipt = cancellationReceipt(payment);
        if (receipt != null && !receipt.failed().equals(new PgRefundCancellationReceipt(success.purchase(), success.attemptId(),
                success.cancellation().getCancellationId(), PgRefundCancellationReceipt.State.FAILED, success.requestedAt(),
                success.taxFreeAmount(), success.vatAmount(), success.reason())))
            throw new BusinessException(ErrorCode.CONFLICT, "성공 응답이 접수한 취소 ID와 다릅니다.");
        if (status == RefundExecutionStatus.SUCCEEDED) {
            if (!Objects.equals(successfulCancellationId, success.cancellation().getCancellationId())
                    || !Objects.equals(cancellationRequestedAt, success.requestedAt())
                    || !Objects.equals(responseCancelledAt, success.cancellation().getCancelledAt())
                    || !Objects.equals(responseTaxFreeAmount, success.taxFreeAmount()) || !Objects.equals(responseVatAmount, success.vatAmount())
                    || !Objects.equals(responseReason, success.reason())) throw new BusinessException(ErrorCode.CONFLICT, "확정한 실행 성공 근거가 다릅니다.");
            return false;
        }
        if (refundConfirmedAt != null && (confirmedCancellations.size() != 1
                || !confirmedCancellations.get(0).getCancellationId().equals(success.cancellation().getCancellationId())
                || confirmedCancellations.get(0).getAmount() != amount))
            throw new BusinessException(ErrorCode.CONFLICT, "최초 환불 완료와 실행 성공의 취소 근거가 다릅니다.");
        cancellationSuccessEventId = eventId; cancellationSucceededAt = at;
        successfulCancellationId = success.cancellation().getCancellationId(); cancellationRequestedAt = success.requestedAt();
        responseCancelledAt = success.cancellation().getCancelledAt(); responseTaxFreeAmount = success.taxFreeAmount();
        responseVatAmount = success.vatAmount(); responseReason = success.reason(); status = RefundExecutionStatus.SUCCEEDED;
        if (refundConfirmedAt == null) {
            refundConfirmedAt = at; refundConfirmationEventId = eventId; refundConfirmationSource = "PG_RESPONSE";
            providerRefundedAt = success.cancellation().getCancelledAt();
            confirmedCancellations = new ArrayList<>(List.of(success.cancellation()));
        }
        lookupSchedule.stop();
        cancellationSchedule.stop();
        return true;
    }
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "refund_cancellation_evidence", joinColumns = @JoinColumn(name = "execution_id"))
    @OrderBy("cancellationId")
    private List<RefundCancellation> confirmedCancellations = new ArrayList<>();

    public boolean confirmRefund(Payment payment, PgRefundConfirmation confirmation, String eventId, Instant observedAt) {
        if (payment == null || confirmation == null || !PaymentValues.text(eventId, 64)
                || !PaymentValues.microsecond(observedAt) || observedAt.isBefore(receivedAt))
            throw new IllegalArgumentException("Refund observation evidence is required");
        payment.requireRefundRequest(request());
        if (!"PORTONE_TEST".equals(payment.getProvider()) || !confirmation.purchase().equals(new PgRefundLookupRequest(
                paymentId, orderNo, amount, currency, payment.getProviderTransactionId(), payment.getProviderOccurredAt())))
            throw new IllegalArgumentException("Verified cancellation does not match the paid purchase");
        if (confirmation.cancelledAt().isAfter(observedAt))
            throw new IllegalArgumentException("Cancellation observation precedes the cancellation");
        if (refundConfirmedAt != null) {
            boolean same = "PG_RESPONSE".equals(refundConfirmationSource)
                    ? confirmedCancellations.size() == 1 && confirmation.cancellations().size() == 1
                        && confirmedCancellations.get(0).getCancellationId().equals(confirmation.cancellations().get(0).getCancellationId())
                        && confirmedCancellations.get(0).getAmount() == confirmation.cancellations().get(0).getAmount()
                    : List.copyOf(confirmedCancellations).equals(confirmation.cancellations())
                        && Objects.equals(providerRefundedAt, confirmation.cancelledAt());
            if (!same)
                throw new BusinessException(ErrorCode.CONFLICT, "이미 확인한 전액 취소 근거가 다릅니다.");
            return false;
        }
        if (status == RefundExecutionStatus.FAILED) throw new BusinessException(ErrorCode.CONFLICT, "확정 실패 뒤 금융 근거를 조사해야 합니다.");
        refundConfirmedAt = observedAt;
        refundConfirmationSource = "PG_QUERY_UNATTRIBUTED";
        providerRefundedAt = confirmation.cancelledAt();
        refundConfirmationEventId = eventId;
        confirmedCancellations = new ArrayList<>(confirmation.cancellations());
        lookupSchedule.stop();
        cancellationSchedule.stop();
        return true;
    }

    public List<RefundCancellation> getConfirmedCancellations() { return List.copyOf(confirmedCancellations); }

    public RefundCancellationPermission prepareCancellation(Instant at) {
        if (!PaymentValues.microsecond(at) || at.isBefore(receivedAt)) {
            throw new IllegalArgumentException("Preparation must have database precision and follow receipt");
        }
        if (status != RefundExecutionStatus.PENDING || refundConfirmedAt != null) throw new BusinessException(ErrorCode.CONFLICT);
        if (firstCancellationPreparedAt == null) firstCancellationPreparedAt = at;
        lookupSchedule.prepare(firstCancellationPreparedAt);
        if (automaticCancellationStoppedAt == null
                && !at.isBefore(RefundCancellationWindow.deadline(firstCancellationPreparedAt))) {
            automaticCancellationStoppedAt = at;
        }
        if (automaticCancellationStoppedAt != null || cancellationBalanceChangedAt != null || receiptCancellationId != null || activeCancellationHoldId != null)
            cancellationSchedule.stop();
        return new RefundCancellationPermission(request(), firstCancellationPreparedAt,
                automaticCancellationStoppedAt != null || cancellationBalanceChangedAt != null || receiptCancellationId != null || activeCancellationHoldId != null);
    }

    public static RefundExecution receive(Payment payment, RefundExecutionRequest request, Instant receivedAt) {
        if (payment == null || !PaymentValues.microsecond(receivedAt)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 근거와 환불 실행 접수 시각이 필요합니다.");
        }
        payment.requireRefundRequest(request);
        RefundExecution execution = new RefundExecution();
        execution.attemptId = request.attemptId(); execution.refundId = request.refundId(); execution.previousAttemptId = request.previousAttemptId();
        execution.requestEventId = request.eventId(); execution.requestOccurredAt = request.occurredAt();
        execution.orderNo = request.orderNo(); execution.paymentId = request.paymentId();
        execution.buyerSubject = request.buyerSubject(); execution.amount = request.amount();
        execution.currency = request.currency(); execution.licenseSuspensionEventId = request.licenseSuspensionEventId();
        execution.receivedAt = receivedAt; execution.status = RefundExecutionStatus.PENDING;
        return execution;
    }

    public RefundExecutionRequest request() {
        return new RefundExecutionRequest(requestEventId, requestOccurredAt, refundId, attemptId, orderNo,
                paymentId, buyerSubject, amount, currency, licenseSuspensionEventId, previousAttemptId);
    }

    public void requireSameRequest(RefundExecutionRequest request) {
        if (!request().equals(request)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 접수한 환불 실행의 근거가 다릅니다.");
        }
    }
}
