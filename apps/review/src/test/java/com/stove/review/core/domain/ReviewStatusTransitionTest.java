package com.stove.review.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 심의 상태머신의 <b>전수</b> 검증 — 허용 전이 × 금지 전이.
 *
 * <pre>
 * REQUESTED ──▶ IN_REVIEW ──▶ APPROVED
 *                        └──▶ REJECTED ──(재신청)──▶ REQUESTED
 * </pre>
 *
 * <p>전수로 도는 이유는, 상태머신에서 위험한 것이 <b>빠뜨린 금지 전이</b>이기 때문이다.
 * 허용 전이는 정상 흐름을 돌려 보면 금방 드러나지만, 금지돼야 할 전이가 열려 있는 것은
 * 아무도 그 경로를 밟지 않는 한 드러나지 않는다.
 *
 * <p>특히 <b>APPROVED 는 종착점</b>이다. 승인된 심의가 다시 움직이면
 * 이미 상품으로 등록돼 판매 중인 게임의 등급이 사후에 바뀌는 셈이 된다.
 */
class ReviewStatusTransitionTest {

    /** 이 표가 사양이다. 여기 없는 전이는 전부 금지된다. */
    private static final Set<List<ReviewStatus>> ALLOWED = Set.of(
            List.of(ReviewStatus.REQUESTED, ReviewStatus.IN_REVIEW),
            List.of(ReviewStatus.IN_REVIEW, ReviewStatus.APPROVED),
            List.of(ReviewStatus.IN_REVIEW, ReviewStatus.REJECTED),
            List.of(ReviewStatus.REJECTED, ReviewStatus.REQUESTED));

    static List<List<ReviewStatus>> allPairs() {
        return java.util.Arrays.stream(ReviewStatus.values())
                .flatMap(from -> java.util.Arrays.stream(ReviewStatus.values())
                        .map(to -> List.of(from, to)))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allPairs")
    @DisplayName("허용표에 있는 전이만 통과한다 — 4×4 전수")
    void onlyListedTransitionsAreAllowed(List<ReviewStatus> pair) {
        ReviewStatus from = pair.get(0);
        ReviewStatus to = pair.get(1);

        assertThat(from.canTransitTo(to))
                .as("%s → %s", from, to)
                .isEqualTo(ALLOWED.contains(pair));
    }

    @ParameterizedTest
    @EnumSource(ReviewStatus.class)
    @DisplayName("어떤 상태도 자기 자신으로 전이하지 않는다 — 재진입은 사양에 없다")
    void noSelfTransition(ReviewStatus status) {
        assertThat(status.canTransitTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ReviewStatus.class)
    @DisplayName("APPROVED 는 종착점이다 — 승인된 심의는 어디로도 가지 않는다")
    void approvedIsTerminal(ReviewStatus to) {
        assertThat(ReviewStatus.APPROVED.canTransitTo(to))
                .as("판매 중인 게임의 등급이 사후에 바뀌면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("REQUESTED 에서 심사를 건너뛰고 바로 승인·반려할 수 없다")
    void cannotSkipReview() {
        assertThat(ReviewStatus.REQUESTED.canTransitTo(ReviewStatus.APPROVED)).isFalse();
        assertThat(ReviewStatus.REQUESTED.canTransitTo(ReviewStatus.REJECTED)).isFalse();
    }

    @Test
    @DisplayName("반려는 재신청으로만 풀린다 — 곧바로 승인되지 않는다")
    void rejectedOnlyReopens() {
        assertThat(ReviewStatus.REJECTED.canTransitTo(ReviewStatus.REQUESTED)).isTrue();
        assertThat(ReviewStatus.REJECTED.canTransitTo(ReviewStatus.APPROVED)).isFalse();
        assertThat(ReviewStatus.REJECTED.canTransitTo(ReviewStatus.IN_REVIEW)).isFalse();
    }

    /** 엔티티가 실제로 그 표를 강제하는지 — enum 만 맞고 호출부가 안 지키면 소용이 없다. */
    @Nested
    @DisplayName("ReviewRequest 가 상태머신을 실제로 강제한다")
    class EntityEnforcesTransitions {

        private ReviewRequest requested() {
            return ReviewRequest.received(1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", false);
        }

        private ReviewRequest inReview() {
            ReviewRequest request = requested();
            request.startReview("BOARD-1");
            return request;
        }

        @Test
        @DisplayName("접수된 신청은 REQUESTED 로 시작한다")
        void startsAsRequested() {
            assertThat(requested().getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        }

        @Test
        @DisplayName("정상 경로: 접수 → 심사 → 승인")
        void happyPathToApproval() {
            ReviewRequest request = inReview();
            request.approve("ALL");

            assertThat(request.getStatus()).isEqualTo(ReviewStatus.APPROVED);
            assertThat(request.getRatingCode()).isEqualTo("ALL");
            assertThat(request.getClosedAt()).isNotNull();
        }

        @Test
        @DisplayName("정상 경로: 심사 → 반려 → 재신청")
        void rejectionThenReopen() {
            ReviewRequest request = inReview();
            request.reject("RATING", "등급 부적합");

            assertThat(request.getStatus()).isEqualTo(ReviewStatus.REJECTED);
            assertThat(request.getRejectReason()).isEqualTo("등급 부적합");

            request.reopen("게임 A (수정)", 25_000L);

            assertThat(request.getStatus()).isEqualTo(ReviewStatus.REQUESTED);
            assertThat(request.getTitle()).isEqualTo("게임 A (수정)");
            assertThat(request.getPrice()).isEqualTo(25_000L);
            // 재신청하면 이전 반려 사유는 지워진다 — 남아 있으면 새 심사에 편견이 된다.
            assertThat(request.getRejectReason()).isNull();
            assertThat(request.getRejectReasonCode()).isNull();
        }

        @Test
        @DisplayName("심사를 건너뛴 승인은 거부된다")
        void approveWithoutReviewIsRejected() {
            assertThatThrownBy(() -> requested().approve("ALL"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("REQUESTED → APPROVED");
        }

        @Test
        @DisplayName("승인된 심의는 다시 반려할 수 없다")
        void approvedCannotBeRejected() {
            ReviewRequest request = inReview();
            request.approve("ALL");

            assertThatThrownBy(() -> request.reject("RATING", "뒤늦은 반려"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("APPROVED → REJECTED");
        }

        @Test
        @DisplayName("승인된 심의는 재신청도 불가능하다")
        void approvedCannotReopen() {
            ReviewRequest request = inReview();
            request.approve("ALL");

            assertThatThrownBy(() -> request.reopen("게임 A", 1L))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("같은 심사를 두 번 시작할 수 없다")
        void cannotStartReviewTwice() {
            ReviewRequest request = inReview();

            assertThatThrownBy(() -> request.startReview("BOARD-2"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("IN_REVIEW → IN_REVIEW");
        }

        @Test
        @DisplayName("재신청 뒤에는 다시 정상 경로를 탄다")
        void reopenedRequestCanBeApproved() {
            ReviewRequest request = inReview();
            request.reject("RATING", "등급 부적합");
            request.reopen("게임 A (수정)", 25_000L);

            assertThatCode(() -> {
                request.startReview("BOARD-2");
                request.approve("TEEN");
            }).doesNotThrowAnyException();

            assertThat(request.getStatus()).isEqualTo(ReviewStatus.APPROVED);
            assertThat(request.getRatingCode()).isEqualTo("TEEN");
        }
    }
}
