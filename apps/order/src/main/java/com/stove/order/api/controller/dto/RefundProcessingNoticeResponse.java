package com.stove.order.api.controller.dto;

import java.time.Instant;

public record RefundProcessingNoticeResponse(Code code, String message, Instant since) {
    public enum Code { RESULT_CONFIRMATION_DELAYED, LICENSE_GRANT_CONFIRMATION_DELAYED }
    public static RefundProcessingNoticeResponse delayed(Instant since) {
        return since==null ? null : new RefundProcessingNoticeResponse(Code.RESULT_CONFIRMATION_DELAYED,
                "환불 결과 확인이 지연되고 있습니다. 결과를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.",since);
    }
    /** 결정 125: 지급 확인(LicenseGranted) 대기가 경계를 넘었을 때. 같은 모양(code·message·since)으로 둔다. */
    public static RefundProcessingNoticeResponse licenseGrantDelayed(Instant since) {
        return since==null ? null : new RefundProcessingNoticeResponse(Code.LICENSE_GRANT_CONFIRMATION_DELAYED,
                "환불 처리 준비가 지연되고 있습니다. 처리를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.",since);
    }
}
