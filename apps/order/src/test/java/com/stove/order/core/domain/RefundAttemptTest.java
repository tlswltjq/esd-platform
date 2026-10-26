package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RefundAttemptTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
    private static final Instant RESOLVED_AT = STARTED_AT.plusSeconds(60);

    @Test void aNewAttemptHasItsOwnIdentityAndKeepsTheRefundIdentity() {
        String refundId = id();

        RefundAttempt attempt = RefundAttempt.start(refundId, 1, STARTED_AT);

        assertThat(attempt.getAttemptId()).matches(OrderValues.UUID_PATTERN).isNotEqualTo(refundId);
        assertThat(attempt.getRefundId()).isEqualTo(refundId);
        assertThat(attempt.getAttemptNo()).isEqualTo(1);
        assertThat(attempt.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(attempt.getResolvedAt()).isNull();
        assertThat(attempt.getFailureReasonCode()).isNull();
        assertThat(attempt.getFailureReason()).isNull();
    }

    @Test void aLaterAttemptDoesNotOverwriteTheEarlierFailureOrItsIdentity() {
        String refundId = id();
        RefundAttempt first = RefundAttempt.start(refundId, 1, STARTED_AT);
        String firstId = first.getAttemptId();
        assertThat(first.fail(RESOLVED_AT, "DECLINED", "외부 환불이 확정 거절되었습니다.")).isTrue();

        RefundAttempt second = RefundAttempt.start(refundId, 2, RESOLVED_AT.plusSeconds(3600));

        assertThat(second.getAttemptId()).isNotEqualTo(firstId);
        assertThat(second.getRefundId()).isEqualTo(first.getRefundId());
        assertThat(second.getAttemptNo()).isEqualTo(2);
        assertThat(second.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(first.getAttemptId()).isEqualTo(firstId);
        assertThat(first.getStatus()).isEqualTo(RefundAttemptStatus.FAILED);
        assertThat(first.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(first.getResolvedAt()).isEqualTo(RESOLVED_AT);
        assertThat(first.getFailureReasonCode()).isEqualTo("DECLINED");
        assertThat(first.getFailureReason()).isEqualTo("외부 환불이 확정 거절되었습니다.");
    }

    @Test void identicalFinalResultsDoNotRewriteTheHistory() {
        RefundAttempt success = RefundAttempt.start(id(), 1, STARTED_AT);
        assertThat(success.succeed(RESOLVED_AT)).isTrue();
        assertThat(success.succeed(RESOLVED_AT)).isFalse();
        assertThat(success.getStatus()).isEqualTo(RefundAttemptStatus.SUCCEEDED);
        assertThat(success.getFailureReasonCode()).isNull();
        RefundAttempt failure = RefundAttempt.start(id(), 1, STARTED_AT);
        assertThat(failure.fail(RESOLVED_AT, "DECLINED", "거절")).isTrue();
        assertThat(failure.fail(RESOLVED_AT, "DECLINED", "거절")).isFalse();
        assertThat(failure.getResolvedAt()).isEqualTo(RESOLVED_AT);
    }

    @Test void aDifferentFinalResultCannotReplaceARecordedResult() {
        RefundAttempt failure = RefundAttempt.start(id(), 1, STARTED_AT);
        failure.fail(RESOLVED_AT, "DECLINED", "거절");

        assertConflict(() -> failure.succeed(RESOLVED_AT));
        assertConflict(() -> failure.fail(RESOLVED_AT, "OTHER", "다른 사유"));
        assertConflict(() -> failure.fail(RESOLVED_AT.plusSeconds(1), "DECLINED", "거절"));
        RefundAttempt success = RefundAttempt.start(id(), 1, STARTED_AT);
        success.succeed(RESOLVED_AT);
        assertConflict(() -> success.fail(RESOLVED_AT, "DECLINED", "거절"));
        assertThat(failure.getFailureReasonCode()).isEqualTo("DECLINED");
        assertThat(failure.getResolvedAt()).isEqualTo(RESOLVED_AT);
        assertThat(success.getStatus()).isEqualTo(RefundAttemptStatus.SUCCEEDED);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "not-a-refund-id"})
    void requiresARefundIdentity(String refundId) {
        assertInvalid(() -> RefundAttempt.start(refundId, 1, STARTED_AT));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void requiresAPositiveAttemptNumber(int attemptNo) {
        assertInvalid(() -> RefundAttempt.start(id(), attemptNo, STARTED_AT));
    }

    @Test void requiresDatabasePrecisionForRecordedTimes() {
        assertInvalid(() -> RefundAttempt.start(id(), 1, null));
        assertInvalid(() -> RefundAttempt.start(id(), 1, STARTED_AT.plusNanos(1)));
        RefundAttempt attempt = RefundAttempt.start(id(), 1, STARTED_AT);
        assertInvalid(() -> attempt.succeed(null));
        assertInvalid(() -> attempt.succeed(RESOLVED_AT.plusNanos(1)));
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
    }

    @Test void aFailureMustHaveAnExplainableBoundedReason() {
        RefundAttempt attempt = RefundAttempt.start(id(), 1, STARTED_AT);
        assertInvalid(() -> attempt.fail(RESOLVED_AT, null, "거절"));
        assertInvalid(() -> attempt.fail(RESOLVED_AT, " ", "거절"));
        assertInvalid(() -> attempt.fail(RESOLVED_AT, "X".repeat(101), "거절"));
        assertInvalid(() -> attempt.fail(RESOLVED_AT, "DECLINED", null));
        assertInvalid(() -> attempt.fail(RESOLVED_AT, "DECLINED", " "));
        assertInvalid(() -> attempt.fail(RESOLVED_AT, "DECLINED", "가".repeat(501)));
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(attempt.getResolvedAt()).isNull();
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private static void assertConflict(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
