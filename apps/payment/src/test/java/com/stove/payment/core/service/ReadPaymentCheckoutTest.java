package com.stove.payment.core.service;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.NOW;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalReviewNotice;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalInput;
import com.stove.payment.core.domain.ApprovalToken;
import com.stove.payment.core.domain.PaymentErrorCode;
import java.time.Clock;
import com.stove.payment.core.domain.PaymentCheckout;
import com.stove.payment.core.domain.PaymentOrderRequest;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadPaymentCheckoutTest {
    private static final URI WEBHOOK = URI.create("https://payments.example/api/v1/payments/webhooks/portone");
    @Mock PaymentRepository payments;
    @Mock PaymentApprovalRepository approvals;

    @Test void returnsTheOwnedPurchasesFixedIdentifierAndNetAmountWithoutChangingItsState() {
        PaymentOrderRequest original = request();
        PaymentOrderRequest discounted = new PaymentOrderRequest(original.eventId(), original.occurredAt(),
                original.orderNo(), original.buyerSubject(), original.productId(), original.productCode(),
                original.title(), original.contentVersion(), 10000, 1000, 9000, "KRW");
        Payment payment = Payment.receive(discounted, "PORTONE_TEST");
        Instant updatedAt = payment.getUpdatedAt();
        Instant nextAttemptAt = payment.getNextAttemptAt();
        when(payments.findByOrderNoAndBuyerSubject(payment.getOrderNo(), payment.getBuyerSubject()))
                .thenReturn(Optional.of(payment));

        PaymentCheckout checkout = service().execute(payment.getOrderNo(), payment.getBuyerSubject());

        assertThat(checkout).isEqualTo(new PaymentCheckout(payment.getPaymentId(), payment.getOrderNo(),
                payment.getTitle(), 9000, "KRW", STORE, CHANNEL, WEBHOOK));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(payment.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(payment.getResultEventId()).isNull();
        verify(payments).findByOrderNoAndBuyerSubject(payment.getOrderNo(), payment.getBuyerSubject());
        verifyNoMoreInteractions(payments);
    }

    @Test void missingOrNotOwnedPurchaseIsNotFoundAndCannotBeCreatedByReadingCheckout() {
        String orderNo = id();
        assertThatThrownBy(() -> service().execute(orderNo, "other-buyer"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(payments).findByOrderNoAndBuyerSubject(orderNo, "other-buyer");
        verifyNoMoreInteractions(payments);
    }

    @Test void repeatedReadsKeepTheSamePurchaseAndPublicSettings() {
        Payment payment = Payment.receive(request(), "PORTONE_TEST");
        when(payments.findByOrderNoAndBuyerSubject(payment.getOrderNo(), payment.getBuyerSubject()))
                .thenReturn(Optional.of(payment));
        assertThat(service().execute(payment.getOrderNo(), payment.getBuyerSubject()))
                .isEqualTo(service().execute(payment.getOrderNo(), payment.getBuyerSubject()));
    }

    @Test void simulatedPurchaseCannotBeSentToTheRealProviderCheckout() {
        Payment payment = Payment.receive(request(), "SIMULATED");
        when(payments.findByOrderNoAndBuyerSubject(payment.getOrderNo(), payment.getBuyerSubject()))
                .thenReturn(Optional.of(payment));
        assertConflict(payment);
    }

    @Test void locallyFinalPaidAndFailedPurchasesCannotOpenANewCheckout() {
        for (PgResult.Decision decision : PgResult.Decision.values()) {
            Payment payment = Payment.receive(request(), "PORTONE_TEST");
            payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(),
                    payment.getAmount(), "KRW", id(), NOW, decision,
                    decision == PgResult.Decision.APPROVED ? null : "DECLINED",
                    decision == PgResult.Decision.APPROVED ? null : "Final decline"), id(), NOW);
            when(payments.findByOrderNoAndBuyerSubject(payment.getOrderNo(), payment.getBuyerSubject()))
                    .thenReturn(Optional.of(payment));
            assertConflict(payment);
        }
    }

    @Test void checkoutClosesExactlyAtTheFixedDeadlineWithoutChangingTheFinancialState() {
        Payment payment=Payment.receive(request(),"PORTONE_TEST");
        when(payments.findByOrderNoAndBuyerSubject(payment.getOrderNo(),payment.getBuyerSubject())).thenReturn(Optional.of(payment));
        Instant expires=payment.getOrderOccurredAt().plusSeconds(1800);
        ReadPaymentCheckout before=new ReadPaymentCheckout(new ReadPayment(payments),STORE,CHANNEL,WEBHOOK,approvals,Clock.fixed(expires.minusNanos(1000),ZoneOffset.UTC));
        assertThat(before.execute(payment.getOrderNo(),payment.getBuyerSubject()).paymentId()).isEqualTo(payment.getPaymentId());
        ReadPaymentCheckout boundary=new ReadPaymentCheckout(new ReadPayment(payments),STORE,CHANNEL,WEBHOOK,approvals,Clock.fixed(expires,ZoneOffset.UTC));
        assertThatThrownBy(()->boundary.execute(payment.getOrderNo(),payment.getBuyerSubject()))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.errorCode()).isEqualTo(PaymentErrorCode.ORDER_EXPIRED));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
    @Test void anUnresolvedApprovalKeepsTheCheckoutBlockedEvenAfterTheOrderDeadline() {
        Payment payment=Payment.receive(request(),"PORTONE_TEST");
        when(payments.findByOrderNoAndBuyerSubject(payment.getOrderNo(),payment.getBuyerSubject())).thenReturn(Optional.of(payment));
        PaymentApproval approval=PaymentApproval.receive(payment,new PaymentApprovalInput(id(),payment.getOrderNo(),"tx-current",new ApprovalToken("token")),"ciphertext",payment.getCreatedAt());
        when(approvals.findFirstByPaymentIdAndStatusInOrderByIdDesc(anyString(),anyList())).thenReturn(Optional.of(approval));
        ReadPaymentCheckout checkout=new ReadPaymentCheckout(new ReadPayment(payments),STORE,CHANNEL,WEBHOOK,approvals,Clock.fixed(payment.getOrderOccurredAt().plusSeconds(1801),ZoneOffset.UTC));
        assertThatThrownBy(()->checkout.execute(payment.getOrderNo(),payment.getBuyerSubject()))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.errorCode()).isEqualTo(PaymentErrorCode.APPROVAL_IN_PROGRESS));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    /** Decision 123: the refused checkout speaks Korean, and a held approval carries its charge notice instead of the reason. */
    @Test void aRefusedCheckoutTellsTheCustomerInKoreanAndAHoldShowsItsChargeNotice() {
        Payment payment=Payment.receive(request(),"PORTONE_TEST");
        when(payments.findByOrderNoAndBuyerSubject(payment.getOrderNo(),payment.getBuyerSubject())).thenReturn(Optional.of(payment));
        PaymentApproval approval=PaymentApproval.receive(payment,new PaymentApprovalInput(id(),payment.getOrderNo(),"tx-current",new ApprovalToken("token")),"ciphertext",payment.getCreatedAt());
        when(approvals.findFirstByPaymentIdAndStatusInOrderByIdDesc(anyString(),anyList())).thenReturn(Optional.of(approval));
        assertThatThrownBy(()->service().execute(payment.getOrderNo(),payment.getBuyerSubject()))
                .isInstanceOfSatisfying(BusinessException.class,error->{
                    assertThat(error.errorCode()).isEqualTo(PaymentErrorCode.APPROVAL_IN_PROGRESS);
                    assertThat(error.getMessage()).isEqualTo("기존 결제 승인을 처리하고 있습니다.");
                });
        approval.recordObservation(new ApprovalObservation(ApprovalObservation.State.PAID,new PgResult("PORTONE_TEST",payment.getPaymentId(),payment.getOrderNo(),
                payment.getAmount(),payment.getCurrency(),"pg-tx",payment.getCreatedAt(),PgResult.Decision.APPROVED,null,null),null),payment.getCreatedAt());
        approval.stopPosts("PAID_WITHOUT_ADMISSION",payment.getCreatedAt());
        assertThatThrownBy(()->service().execute(payment.getOrderNo(),payment.getBuyerSubject()))
                .isInstanceOfSatisfying(BusinessException.class,error->{
                    assertThat(error.errorCode()).isEqualTo(PaymentErrorCode.APPROVAL_IN_PROGRESS);
                    assertThat(error.getMessage()).isEqualTo(ApprovalReviewNotice.of(approval,null).orElseThrow().message())
                            .startsWith("결제 금액 10,000원이 청구된 것으로").doesNotContain("PAID_WITHOUT_ADMISSION");
                });
    }

    private void assertConflict(Payment payment) {
        assertThatThrownBy(() -> service().execute(payment.getOrderNo(), payment.getBuyerSubject()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    private ReadPaymentCheckout service() { return new ReadPaymentCheckout(new ReadPayment(payments), STORE, CHANNEL, WEBHOOK,approvals,Clock.systemUTC()); }
}
