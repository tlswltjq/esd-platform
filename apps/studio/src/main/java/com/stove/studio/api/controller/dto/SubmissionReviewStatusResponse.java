package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.Submission;
import com.stove.studio.core.domain.SubmissionGate;
import com.stove.studio.core.domain.SubmissionGateStatus;
import com.stove.studio.core.domain.SubmissionStatus;
import java.util.List;

/** 창작자에게 보여 주는 심사 진행 상황. 운영용 심사 API의 권한을 우회하지 않는다. */
public record SubmissionReviewStatusResponse(
        Long submissionId,
        SubmissionStatus submissionStatus,
        List<Gate> gates,
        String nextAction
) {
    public static SubmissionReviewStatusResponse from(Submission submission, List<SubmissionGate> source) {
        List<Gate> gates = source.stream().map(Gate::from).toList();
        String nextAction;
        if (gates.stream().anyMatch(value -> value.status() == SubmissionGateStatus.CHANGES_REQUESTED)) {
            nextAction = "변경 요청을 반영한 새 상점·가격·등급 revision을 만든 뒤 재제출하세요.";
        } else if (submission.getStatus() == SubmissionStatus.READY_FOR_RELEASE) {
            nextAction = "모든 심사 게이트가 승인되었습니다. 출시를 예약할 수 있습니다.";
        } else if (gates.stream().anyMatch(value -> value.status() == SubmissionGateStatus.PENDING)) {
            nextAction = "심사 결과를 기다리는 중입니다.";
        } else {
            nextAction = "제출 스냅샷의 다음 상태를 확인하세요.";
        }
        return new SubmissionReviewStatusResponse(submission.getId(), submission.getStatus(), gates, nextAction);
    }

    public record Gate(String reviewType, SubmissionGateStatus status, String reasonCode, String feedback) {
        static Gate from(SubmissionGate gate) {
            return new Gate(gate.getReviewType(), gate.getStatus(), gate.getReasonCode(), gate.getFeedback());
        }
    }
}
