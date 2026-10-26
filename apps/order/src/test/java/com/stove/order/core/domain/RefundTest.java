package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RefundTest {
    private static final Instant PAYMENT_CONFIRMED_AT = Instant.parse("2026-10-25T10:00:00.123456Z");

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-25T13:59:59.999999Z", "2026-10-25T14:00:00.123456Z"})
    void anAutomaticallyAllowedRefundStartsWaitingForLicenseGrant(String receivedAtText) {
        Order paid = paidOrder();
        PaymentResult originalPayment = paid.paymentResult();
        Instant receivedAt = Instant.parse(receivedAtText);

        Refund refund = Refund.request(paid, receivedAt);

        assertThat(refund.getRefundId()).isNotBlank();
        assertThat(refund.getOrderNo()).isEqualTo(paid.getOrderNo());
        assertThat(refund.getReceivedAt()).isEqualTo(receivedAt);
        assertThat(refund.isAutomaticRefundEligible()).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.paymentResult()).isEqualTo(originalPayment);
    }

    @Test
    void aLateRequestStartsWaitingForReviewWithoutChangingTheOriginalPayment() {
        Order paid = paidOrder();
        PaymentResult originalPayment = paid.paymentResult();
        Instant receivedAt = Instant.parse("2026-10-25T14:00:00.123457Z");

        Refund refund = Refund.request(paid, receivedAt);

        assertThat(refund.getRefundId()).isNotBlank();
        assertThat(refund.getOrderNo()).isEqualTo(paid.getOrderNo());
        assertThat(refund.getReceivedAt()).isEqualTo(receivedAt);
        assertThat(refund.isAutomaticRefundEligible()).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.paymentResult()).isEqualTo(originalPayment);
    }

    @Test
    void requiresAnOrderToCreateARefund() {
        assertThatThrownBy(() -> Refund.request(null, PAYMENT_CONFIRMED_AT))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void requiresARecordedReceiptTime() {
        Order paid = paidOrder();

        assertThatThrownBy(() -> Refund.request(paid, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "FAILED"})
    void onlyAPaidOrderCanStartARefund(OrderStatus status) {
        Order unpaid = order();
        if (status == OrderStatus.FAILED) {
            unpaid.apply(new PaymentResult(id(), PAYMENT_CONFIRMED_AT, unpaid.getOrderNo(),
                    unpaid.getRequestEventId(), id(), unpaid.getBuyerSubject(), unpaid.getAmount(),
                    unpaid.getCurrency(), OrderStatus.FAILED, "DECLINED", "승인이 거절되었습니다."));
        }

        assertThatThrownBy(() -> Refund.request(unpaid, PAYMENT_CONFIRMED_AT))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(unpaid.getStatus()).isEqualTo(status);
    }

    private static Order paidOrder() {
        Order paid = order();
        PaymentResult result = new PaymentResult(id(), PAYMENT_CONFIRMED_AT, paid.getOrderNo(),
                paid.getRequestEventId(), id(), paid.getBuyerSubject(), paid.getAmount(),
                paid.getCurrency(), OrderStatus.PAID, null, null);
        paid.apply(result);
        return paid;
    }

    @Test
    void confirmingTheGrantAdvancesOnceWithoutChangingTheOriginalEligibility() {
        Order paid = paidOrder();
        Instant receivedAt = PAYMENT_CONFIRMED_AT.plusSeconds(3 * 3600);
        Refund refund = Refund.request(paid, receivedAt);
        LicenseGrant grant = grant(paid);

        assertThat(refund.confirmLicenseGrant(grant)).isTrue();
        assertThat(refund.confirmLicenseGrant(grant)).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(refund.getReceivedAt()).isEqualTo(receivedAt);
        assertThat(refund.isAutomaticRefundEligible()).isTrue();
    }

    @Test
    void grantConfirmationDoesNotApproveAManualReview() {
        Order paid = paidOrder();
        Refund refund = Refund.request(paid, PAYMENT_CONFIRMED_AT.plusSeconds(5 * 3600));

        assertThat(refund.confirmLicenseGrant(grant(paid))).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(refund.isAutomaticRefundEligible()).isFalse();
    }

    @Test
    void rejectsMissingAndOtherOrdersGrantConfirmation() {
        Order paid = paidOrder();
        Refund refund = Refund.request(paid, PAYMENT_CONFIRMED_AT);

        assertThatThrownBy(() -> refund.confirmLicenseGrant(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> refund.confirmLicenseGrant(grant(paidOrder())))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
    }

    private static LicenseGrant grant(Order order) {
        return new LicenseGrant(id(), PAYMENT_CONFIRMED_AT.plusSeconds(60), id(), order.getOrderNo(),
                order.getBuyerSubject(), order.getProductId(), order.getProductCode(), order.getPaymentId());
    }
}
