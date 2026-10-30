package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundProviderAmountsTest {
    private static final Instant NOW = Payment.now();

    @Test void theRefundPurchaseUsesTheStoredApprovalAmountsAndKeepsUnknownAmountsUnknown() {
        Payment known = paid(new PgPaymentAmounts(9976, 24));
        assertThat(PgRefundLookupRequest.from(known).paymentAmounts()).isEqualTo(new PgPaymentAmounts(9976, 24));
        assertThat(PgRefundLookupRequest.from(known).amount()).isEqualTo(10000);
        assertThat(PgRefundLookupRequest.from(paid(null)).paymentAmounts()).isNull();
        assertThatThrownBy(() -> new PgRefundLookupRequest(known.getPaymentId(), known.getOrderNo(), 10000, "KRW",
                known.getProviderTransactionId(), known.getProviderOccurredAt(), new PgPaymentAmounts(9975, 24)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pointRefundEvidenceIsOptionalAndIsNotTheWholeSupplierDiscount() {
        RefundCancellation unknown = new RefundCancellation("cancel", 10000, NOW);
        RefundCancellation zero = new RefundCancellation("cancel", 10000, NOW, 0L);
        assertThat(unknown.getEasyPayDiscountAmount()).isNull();
        assertThat(unknown).isNotEqualTo(zero);
        for (long amount : List.of(-1L, 10001L)) {
            assertThatThrownBy(() -> new RefundCancellation("cancel", 10000, NOW, amount))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void everyReceiptAndSuccessMustRespectTheKnownApprovalDiscountLimit() {
        PgRefundLookupRequest purchase = PgRefundLookupRequest.from(paid(new PgPaymentAmounts(9976, 24)));
        for (long amount : List.of(-1L, 25L)) {
            assertThatThrownBy(() -> receipt(purchase, amount)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> success(purchase, id(), amount)).isInstanceOf(IllegalArgumentException.class);
        }
        PgRefundCancellationReceipt receipt = receipt(purchase, 12L);
        assertThat(receipt.failed().easyPayDiscountAmount()).isEqualTo(12L);
        assertThat(receipt.failed().purchase()).isEqualTo(purchase);
        assertThatThrownBy(() -> receipt(PgRefundLookupRequest.from(paid(null)), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void distinctCancellationsCannotReturnMoreReportedPointsThanTheOriginalDiscount() {
        PgRefundLookupRequest purchase = PgRefundLookupRequest.from(paid(new PgPaymentAmounts(9976, 24)));
        assertThatThrownBy(() -> new PgRefundConfirmation(purchase, List.of(
                new RefundCancellation("one", 5000, NOW, 13L), new RefundCancellation("two", 5000, NOW, 12L)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PgRefundConfirmation(purchase, List.of(
                new RefundCancellation("one", 5000, NOW, 12L), new RefundCancellation("two", 5000, NOW, 12L)), NOW).cancellations())
                .hasSize(2);
    }

    @Test void directSuccessPreservesItsPointAmountAndAChangedQueryCannotReplaceIt() {
        Payment payment = paid(new PgPaymentAmounts(9976, 24));
        RefundExecution current = prepared(payment);
        PgRefundLookupRequest purchase = PgRefundLookupRequest.from(payment);
        PgResult original = payment.supplierResult();
        assertThat(current.confirmCancellationSuccess(payment, success(purchase, current.getAttemptId(), 12L), id(), NOW)).isTrue();
        assertThat(current.getResponseEasyPayDiscountAmount()).isEqualTo(12L);
        assertThat(current.getConfirmedCancellations().getFirst().getEasyPayDiscountAmount()).isEqualTo(12L);
        assertThatThrownBy(() -> current.confirmCancellationSuccess(payment,
                success(purchase, current.getAttemptId(), 13L), id(), NOW)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> current.confirmRefund(payment, new PgRefundConfirmation(purchase,
                List.of(new RefundCancellation("cancel", 10000, NOW, 13L)), NOW), id(), NOW))
                .isInstanceOf(BusinessException.class);
        assertThat(payment.supplierResult()).isEqualTo(original);
    }

    @Test void aFailureReceiptKeepsItsPointsAndDifferentKnownPointsCannotReplaceIt() {
        Payment payment = paid(new PgPaymentAmounts(9976, 24));
        RefundExecution current = prepared(payment);
        PgRefundLookupRequest purchase = PgRefundLookupRequest.from(payment);
        PgRefundCancellationReceipt original = new PgRefundCancellationReceipt(purchase, current.getAttemptId(), "cancel",
                PgRefundCancellationReceipt.State.REQUESTED, NOW.minusSeconds(2), 0, 0, "reason", 12L);
        current.recordCancellationReceipt(payment, original, NOW);
        assertThat(current.cancellationReceipt(payment)).isEqualTo(original);
        assertThatThrownBy(() -> current.recordCancellationReceipt(payment, new PgRefundCancellationReceipt(purchase,
                current.getAttemptId(), "cancel", PgRefundCancellationReceipt.State.FAILED, NOW.minusSeconds(2), 0, 0, "reason", 13L), NOW))
                .isInstanceOf(BusinessException.class);
        assertThat(current.cancellationReceipt(payment).easyPayDiscountAmount()).isEqualTo(12L);
    }

    private Payment paid(PgPaymentAmounts amounts) {
        Payment payment = Payment.receive(request(), "PORTONE_TEST");
        payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW",
                "pg-original", NOW.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null, amounts), id(), NOW.minusSeconds(30));
        return payment;
    }

    private RefundExecution prepared(Payment payment) {
        RefundExecution current = RefundExecution.receive(payment, execution(payment), NOW.minusSeconds(10));
        current.prepareCancellation(NOW.minusSeconds(5));
        current.freezeCancellationBody("frozen body");
        return current;
    }

    private PgRefundCancellationReceipt receipt(PgRefundLookupRequest purchase, Long points) {
        return new PgRefundCancellationReceipt(purchase, id(), "cancel", PgRefundCancellationReceipt.State.REQUESTED,
                NOW.minusSeconds(2), 0, 0, "reason", points);
    }

    private PgRefundCancellationSuccess success(PgRefundLookupRequest purchase, String attemptId, Long points) {
        return new PgRefundCancellationSuccess(purchase, attemptId, new RefundCancellation("cancel", 10000, null, points),
                NOW.minusSeconds(2), 0, 0, "reason");
    }
}
