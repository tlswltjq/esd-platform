package com.stove.studio.core.domain.submission;

import java.util.List;

/**
 * 제출물과 유형별 관문의 심사 진행 조회 결과.
 *
 * @param submission 심사 진행 상황을 조회하는 제출물.
 * @param gates 제출물에 필요한 유형별 출시 관문 목록.
 */
public record SubmissionReviewStatus(Submission submission, List<SubmissionGate> gates) {
}
