package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundStatus;
import java.time.Instant;

public record RefundProcessingNoticeResponse(Code code, String message, Instant since) {
    public enum Code {
        RESULT_CONFIRMATION_DELAYED, LICENSE_GRANT_CONFIRMATION_DELAYED,
        LICENSE_SUSPENSION_CONFIRMATION_DELAYED, LICENSE_RESTORATION_CONFIRMATION_DELAYED, APPROVAL_STOP_CONFIRMATION_DELAYED
    }
    private static final String PREPARATION_DELAYED = "환불 처리 준비가 지연되고 있습니다. 처리를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.";
    /** PG가 취소하지 않은 것이 확정된 상태다. 돈은 돌아가지 않았고, 이용권이 돌아오면 재신청할 수 있다(결정 134). */
    private static final String RESTORATION_DELAYED = "결제 취소가 처리되지 않아 이용권을 되돌리고 있으나 지연되고 있습니다. 결제 금액은 환불되지 않았으며, 이용권이 돌아오면 다시 신청할 수 있습니다.";

    public static RefundProcessingNoticeResponse delayed(Instant since) {
        return since==null ? null : new RefundProcessingNoticeResponse(Code.RESULT_CONFIRMATION_DELAYED,
                "환불 결과 확인이 지연되고 있습니다. 결과를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.",since);
    }
    /** 결정 125: 지급 확인(LicenseGranted) 대기가 경계를 넘었을 때. 같은 모양(code·message·since)으로 둔다. */
    public static RefundProcessingNoticeResponse licenseGrantDelayed(Instant since) {
        return since==null ? null : new RefundProcessingNoticeResponse(Code.LICENSE_GRANT_CONFIRMATION_DELAYED, PREPARATION_DELAYED, since);
    }
    /** 결정 125 범위 확장: 지급 확인·정지·복구·승인 중단 대기가 경계를 넘었을 때 상태에 맞는 안내. */
    public static RefundProcessingNoticeResponse licenseWaitDelayed(RefundStatus status, Instant since) {
        if (since == null || status == null) return null;
        return switch (status) {
            case PENDING_LICENSE_GRANT -> licenseGrantDelayed(since);
            case PENDING_LICENSE_SUSPENSION -> new RefundProcessingNoticeResponse(Code.LICENSE_SUSPENSION_CONFIRMATION_DELAYED, PREPARATION_DELAYED, since);
            case PENDING_APPROVAL_STOP -> new RefundProcessingNoticeResponse(Code.APPROVAL_STOP_CONFIRMATION_DELAYED, PREPARATION_DELAYED, since);
            case PENDING_LICENSE_RESTORATION -> new RefundProcessingNoticeResponse(Code.LICENSE_RESTORATION_CONFIRMATION_DELAYED, RESTORATION_DELAYED, since);
            default -> null;
        };
    }
}
