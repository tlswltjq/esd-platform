package com.stove.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewRequestTest {

    private ReviewRequest received() {
        return ReviewRequest.received(1L, "GAME-TEST-001", "테스트 게임", 1001L, 10000L, "KRW", false);
    }

    @Test
    @DisplayName("접수 직후에는 승인할 수 없다 — 심사 착수를 거쳐야 한다")
    void cannotApproveBeforeReview() {
        assertThatThrownBy(() -> received().approve("15")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("접수 → 심사 → 승인 상태 전이")
    void approveFlow() {
        ReviewRequest request = received();
        request.startReview("GRAC-2026-00001");
        request.approve("15");

        assertThat(request.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(request.getRatingCode()).isEqualTo("15");
        assertThat(request.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("반려된 건은 재신청으로만 되살릴 수 있다")
    void rejectThenReopen() {
        ReviewRequest request = received();
        request.startReview("GRAC-2026-00001");
        request.reject("CONTENT", "선정성 등급 기준 초과");
        assertThat(request.getStatus()).isEqualTo(ReviewStatus.REJECTED);

        assertThatThrownBy(() -> request.approve("15")).isInstanceOf(BusinessException.class);

        request.reopen("테스트 게임 v2", 12000L);
        assertThat(request.getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        assertThat(request.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("승인된 건은 어떤 상태로도 전이되지 않는다")
    void approvedIsTerminal() {
        ReviewRequest request = received();
        request.startReview(null);
        request.approve("ALL");

        assertThatThrownBy(() -> request.reject("X", "y")).isInstanceOf(BusinessException.class);
    }
}
