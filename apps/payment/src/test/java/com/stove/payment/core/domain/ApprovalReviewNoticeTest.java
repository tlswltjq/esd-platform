package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.payment.core.domain.ApprovalReviewNotice.Code;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Decision 123: a held or review-closed approval tells the customer whether money was charged, never why it was held. */
class ApprovalReviewNoticeTest {
    private static final String CHARGED = "결제 금액 10,000원이 청구된 것으로 확인되어 담당자가 확인하고 있습니다. 확인이 끝나면 청구된 금액은 취소되고 이 주문은 종료됩니다. 같은 상품을 지금 다시 주문하면 중복 청구될 수 있으니 확인 결과를 기다려 주세요.";
    private static final String CHARGE_CANCELLED = "결제 금액 10,000원은 전액 취소되었습니다. 카드사 사정에 따라 취소 반영까지 시간이 걸릴 수 있습니다. 담당자 확인이 끝나면 이 주문은 종료됩니다.";
    private static final String CHARGE_UNCONFIRMED = "결제 확인이 지연되고 있습니다. 결제사와 청구 여부를 확인하고 있으며, 청구된 금액이 있다면 확인 뒤 취소되고 이 주문은 종료됩니다. 별도로 다시 결제하지 마세요.";
    private static final String NOT_CHARGED = "결제 확인이 지연되고 있습니다. 현재까지 청구된 금액은 없습니다. 담당자 확인이 끝나면 이 주문은 종료되며, 새 주문으로 다시 구매하실 수 있습니다.";
    private static final String CLOSED_CHARGE_CANCELLED = "결제 확인 결과 이 주문은 종료되었습니다. 청구되었던 10,000원은 전액 취소되었습니다. 새 주문으로 다시 구매해 주세요.";
    private static final String CLOSED_NOT_CHARGED = "결제 확인 결과 이 주문은 종료되었습니다. 청구된 금액은 없습니다. 새 주문으로 다시 구매해 주세요.";

    private final Payment payment = Payment.receive(request(), "PORTONE_TEST");
    private final Instant at = Payment.now();

    @Test void everyKnownHoldReasonHasAChargeBundle() {
        // A new hold reason added to the gauge list without a bundle fails here (decisions 123·124·126).
        assertThat(ApprovalReviewNotice.BUNDLES.keySet()).isEqualTo(Set.copyOf(ApprovalReviewReason.KNOWN));
        for (String reason : ApprovalReviewReason.KNOWN) {
            PaymentApproval held = held(reason, ApprovalObservation.State.READY);
            assertThat(ApprovalReviewNotice.of(held, null)).as(reason).isPresent();
        }
    }

    @Test void invalidEvidenceIsUnconfirmedWhateverTheLatestObservation() {
        for (ApprovalObservation.State latest : ApprovalObservation.State.values())
            assertCode("PG_INVALID_EVIDENCE", latest, Code.CHARGE_UNCONFIRMED, CHARGE_UNCONFIRMED);
        PaymentApproval unobserved = approval(); unobserved.stopPosts("PG_INVALID_EVIDENCE", at);
        assertThat(notice(unobserved).code()).isEqualTo(Code.CHARGE_UNCONFIRMED);
    }

    /** Decision 132: every other hold reason takes its code from the latest valid PG observation. */
    @Test void everyOtherReasonFollowsTheLatestObservationForEveryState() {
        for (String reason : ApprovalReviewReason.KNOWN) {
            if ("PG_INVALID_EVIDENCE".equals(reason)) continue;
            for (ApprovalObservation.State latest : ApprovalObservation.State.values()) {
                Code code = switch (latest) {
                    case PAID -> Code.CHARGED;
                    case CANCELLED -> Code.CHARGE_CANCELLED;
                    case PENDING -> Code.CHARGE_UNCONFIRMED;
                    case READY, FAILED -> Code.NOT_CHARGED;
                };
                assertThat(notice(held(reason, latest)).code()).as(reason + "/" + latest).isEqualTo(code);
                PaymentApproval failedLookup = held(reason, latest);
                failedLookup.recordInspectionFailure("UNAVAILABLE", at.plusSeconds(5));
                assertThat(notice(failedLookup).code()).as(reason + "/" + latest + "/lookup failed").isEqualTo(Code.CHARGE_UNCONFIRMED);
            }
            PaymentApproval unobserved = approval(); unobserved.stopPosts(reason, at);
            assertThat(notice(unobserved).code()).as(reason + "/unobserved").isEqualTo(Code.CHARGE_UNCONFIRMED);
        }
    }

    @Test void eachCodeUsesTheDecisionWording() {
        assertCode("PAID_WITHOUT_ADMISSION", ApprovalObservation.State.PAID, Code.CHARGED, CHARGED);
        assertCode("PG_CANCELLED", ApprovalObservation.State.CANCELLED, Code.CHARGE_CANCELLED, CHARGE_CANCELLED);
        assertCode("PG_PENDING_AT_ORDER_EXPIRY", ApprovalObservation.State.PENDING, Code.CHARGE_UNCONFIRMED, CHARGE_UNCONFIRMED);
        assertCode("CONFIRM_WINDOW_EXPIRED", ApprovalObservation.State.READY, Code.NOT_CHARGED, NOT_CHARGED);
    }

    @Test void aConsoleCancellationAfterAPaidHoldShowsTheReturnedCharge() {
        PaymentApproval paid = held("PAID_WITHOUT_ADMISSION", ApprovalObservation.State.PAID);
        assertThat(notice(paid).code()).isEqualTo(Code.CHARGED);
        paid.recordObservation(observation(ApprovalObservation.State.CANCELLED), at.plusSeconds(5));
        assertThat(notice(paid)).isEqualTo(new ApprovalReviewNotice(Code.CHARGE_CANCELLED, CHARGE_CANCELLED, at));
        paid.recordInspectionFailure("UNAVAILABLE", at.plusSeconds(10));
        assertThat(notice(paid).code()).isEqualTo(Code.CHARGE_UNCONFIRMED);
        paid.recordObservation(observation(ApprovalObservation.State.CANCELLED), at.plusSeconds(15));
        assertThat(notice(paid).code()).isEqualTo(Code.CHARGE_CANCELLED);
    }

    @Test void theNoticeStartsWhenTheHoldStartedAndNeverShowsTheReason() {
        for (String reason : ApprovalReviewReason.KNOWN) {
            PaymentApproval held = held(reason, ApprovalObservation.State.READY);
            ApprovalReviewNotice notice = notice(held);
            assertThat(notice.since()).isEqualTo(held.getReviewStartedAt()).isEqualTo(at);
            assertThat(notice.toString()).doesNotContain(reason, held.getPaymentId(), held.getSupplierAttemptId());
        }
    }

    @Test void aClosedHoldTellsWhetherTheChargeWasReturned() {
        Closed cancelled = closed("PAID_WITHOUT_ADMISSION", ApprovalObservation.State.CANCELLED);
        assertThat(ApprovalReviewNotice.of(cancelled.approval(), cancelled.closure()))
                .contains(new ApprovalReviewNotice(Code.CLOSED_CHARGE_CANCELLED, CLOSED_CHARGE_CANCELLED, at));
        for (ApprovalObservation.State evidence : new ApprovalObservation.State[] {ApprovalObservation.State.READY, ApprovalObservation.State.FAILED}) {
            Closed nothing = closed("PG_REQUEST_REJECTED", evidence);
            assertThat(ApprovalReviewNotice.of(nothing.approval(), nothing.closure())).as(evidence.name())
                    .contains(new ApprovalReviewNotice(Code.CLOSED_NOT_CHARGED, CLOSED_NOT_CHARGED, at));
        }
    }

    @Test void approvalsThatWereNeverHeldOrEndedWithoutAClosureHaveNoNotice() {
        PaymentApproval requested = approval();
        assertThat(ApprovalReviewNotice.of(requested, null)).isEmpty();
        PaymentApproval expired = approval(); expired.expire(at.plusSeconds(1));
        assertThat(ApprovalReviewNotice.of(expired, null)).isEmpty();
        Closed closed = closed("PG_REQUEST_REJECTED", ApprovalObservation.State.READY);
        assertThat(ApprovalReviewNotice.of(closed.approval(), null)).isEmpty();
        Closed other = closed("PG_REQUEST_REJECTED", ApprovalObservation.State.READY);
        assertThat(ApprovalReviewNotice.of(closed.approval(), other.closure())).isEmpty();
    }

    private void assertCode(String reason, ApprovalObservation.State latest, Code code, String message) {
        PaymentApproval held = held(reason, latest);
        assertThat(ApprovalReviewNotice.of(held, null)).as(reason + "/" + latest).contains(new ApprovalReviewNotice(code, message, at));
    }
    private ApprovalReviewNotice notice(PaymentApproval approval) { return ApprovalReviewNotice.of(approval, null).orElseThrow(); }
    private PaymentApproval approval() {
        return PaymentApproval.receive(payment, new PaymentApprovalInput(id(), payment.getOrderNo(), "tx-" + id(), new ApprovalToken("secret-token")),
                "encrypted-body", at);
    }
    private PaymentApproval held(String reason, ApprovalObservation.State latest) {
        PaymentApproval approval = approval();
        approval.recordObservation(observation(latest), at);
        approval.stopPosts(reason, at);
        return approval;
    }
    private Closed closed(String reason, ApprovalObservation.State evidence) {
        PaymentApproval approval = held(reason, ApprovalObservation.State.READY);
        String lease = id();
        approval.claimForClosure(lease, at.plusSeconds(1), Duration.ofSeconds(60));
        ApprovalClosure record = ApprovalClosure.record(approval, new ApprovalClosureRequest(id(), approval.getApprovalId(), "usr_operator", "종결"),
                observation(evidence), at.plusSeconds(1), at.plusSeconds(2));
        approval.closeAfterReview(observation(evidence), lease, at.plusSeconds(2));
        return new Closed(approval, record);
    }
    private record Closed(PaymentApproval approval, ApprovalClosure closure) { }
    private ApprovalObservation observation(ApprovalObservation.State state) {
        if (state == ApprovalObservation.State.PAID)
            return new ApprovalObservation(state, new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(),
                    payment.getCurrency(), "pg-tx", at, PgResult.Decision.APPROVED, null, null), null);
        if (state == ApprovalObservation.State.FAILED) return new ApprovalObservation(state, null, at);
        return new ApprovalObservation(state, null, null);
    }
}
