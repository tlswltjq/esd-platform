package com.stove.payment.core.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The customer notice of a held or review-closed approval (decision 123). It says whether money was charged, using the
 * purchase amount, and never exposes the hold reason, which stays an operator record. Order responses and events do not
 * carry it.
 */
public record ApprovalReviewNotice(Code code, String message, Instant since) {
    public enum Code { CHARGED, CHARGE_CANCELLED, CHARGE_UNCONFIRMED, NOT_CHARGED, CLOSED_CHARGE_CANCELLED, CLOSED_NOT_CHARGED }

    /** How a hold reason decides the charge code: fixed by the reason, or by the latest PG lookup (decisions 123·124·126). */
    enum Bundle { CHARGED, CHARGE_CANCELLED, CHARGE_UNCONFIRMED, BY_OBSERVATION }

    /** Every reason in {@link ApprovalReviewReason#KNOWN} has a bundle; a test fails when a new reason is added without one. */
    static final Map<String, Bundle> BUNDLES = Map.of(
            "PAID_WITHOUT_ADMISSION", Bundle.CHARGED,
            "PG_CANCELLED", Bundle.CHARGE_CANCELLED,
            "PG_INVALID_EVIDENCE", Bundle.CHARGE_UNCONFIRMED,
            "PG_PENDING_AT_ORDER_EXPIRY", Bundle.CHARGE_UNCONFIRMED,
            "PG_REQUEST_REJECTED", Bundle.BY_OBSERVATION,
            "ORDER_ADMISSION_CONFLICT", Bundle.BY_OBSERVATION,
            "ORDER_EXPIRY_CONFLICT", Bundle.BY_OBSERVATION,
            PaymentApproval.CONFIRM_WINDOW_EXPIRED, Bundle.BY_OBSERVATION);

    public ApprovalReviewNotice {
        if (code == null || !PaymentValues.text(message, 500)) throw new IllegalArgumentException("A bounded customer notice is required");
    }

    /**
     * A currently held approval gets its charge notice; an approval closed by an operator gets the closure notice of its
     * own closure record. Every other approval has none.
     */
    public static Optional<ApprovalReviewNotice> of(PaymentApproval approval, ApprovalClosure closure) {
        if (approval == null) return Optional.empty();
        if (approval.getStatus() == PaymentApprovalStatus.REVIEW_REQUIRED) return Optional.of(held(approval));
        if (approval.getStatus() == PaymentApprovalStatus.FAILED && closure != null && approval.getApprovalId().equals(closure.getApprovalId()))
            return Optional.of(closed(approval, closure));
        return Optional.empty();
    }

    private static ApprovalReviewNotice held(PaymentApproval approval) {
        Code code = switch (BUNDLES.getOrDefault(approval.getLastErrorCode(), Bundle.BY_OBSERVATION)) {
            case CHARGED -> Code.CHARGED;
            case CHARGE_CANCELLED -> Code.CHARGE_CANCELLED;
            case CHARGE_UNCONFIRMED -> Code.CHARGE_UNCONFIRMED;
            case BY_OBSERVATION -> byObservation(approval);
        };
        return notice(code, approval);
    }

    /**
     * The latest lookup decides: still PENDING or failed means the charge is unconfirmed, READY or FAILED means nothing was
     * charged so far. A held approval whose latest lookup reports money (PAID, CANCELLED) or that has no lookup at all is
     * outside decision 123's bundles; it is shown as unconfirmed, which never claims that nothing was charged.
     */
    private static Code byObservation(PaymentApproval approval) {
        if (approval.getLastInspectionError() != null) return Code.CHARGE_UNCONFIRMED;
        ApprovalObservation.State latest = approval.getLatestPgState();
        if (latest == ApprovalObservation.State.READY || latest == ApprovalObservation.State.FAILED) return Code.NOT_CHARGED;
        return Code.CHARGE_UNCONFIRMED;
    }

    private static ApprovalReviewNotice closed(PaymentApproval approval, ApprovalClosure closure) {
        return notice(closure.getEvidenceState() == ApprovalObservation.State.CANCELLED ? Code.CLOSED_CHARGE_CANCELLED : Code.CLOSED_NOT_CHARGED, approval);
    }

    private static ApprovalReviewNotice notice(Code code, PaymentApproval approval) {
        String amount = String.format(Locale.ROOT, "%,d", approval.getAmount());
        String message = switch (code) {
            case CHARGED -> "결제 금액 " + amount + "원이 청구된 것으로 확인되어 담당자가 확인하고 있습니다. 확인이 끝나면 청구된 금액은 취소되고 이 주문은 종료됩니다. 같은 상품을 지금 다시 주문하면 중복 청구될 수 있으니 확인 결과를 기다려 주세요.";
            case CHARGE_CANCELLED -> "결제 금액 " + amount + "원은 전액 취소되었습니다. 카드사 사정에 따라 취소 반영까지 시간이 걸릴 수 있습니다. 담당자 확인이 끝나면 이 주문은 종료됩니다.";
            case CHARGE_UNCONFIRMED -> "결제 확인이 지연되고 있습니다. 결제사와 청구 여부를 확인하고 있으며, 청구된 금액이 있다면 확인 뒤 취소되고 이 주문은 종료됩니다. 별도로 다시 결제하지 마세요.";
            case NOT_CHARGED -> "결제 확인이 지연되고 있습니다. 현재까지 청구된 금액은 없습니다. 담당자 확인이 끝나면 이 주문은 종료되며, 새 주문으로 다시 구매하실 수 있습니다.";
            case CLOSED_CHARGE_CANCELLED -> "결제 확인 결과 이 주문은 종료되었습니다. 청구되었던 " + amount + "원은 전액 취소되었습니다. 새 주문으로 다시 구매해 주세요.";
            case CLOSED_NOT_CHARGED -> "결제 확인 결과 이 주문은 종료되었습니다. 청구된 금액은 없습니다. 새 주문으로 다시 구매해 주세요.";
        };
        return new ApprovalReviewNotice(code, message, approval.getReviewStartedAt());
    }
}
