package com.stove.review.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.InfraContainers;
import com.stove.review.core.domain.ReviewRequest;
import com.stove.review.core.domain.ReviewRequestRepository;
import com.stove.review.core.domain.ReviewStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 심의 목록 조회 — {@code GET /api/v1/reviews} 가 타는 경로.
 *
 * <p>상태 파라미터가 없으면 전체, 있으면 그 상태만 준다. 그동안 이 분기는 어느 테스트도
 * 실행하지 않았다. 컨트롤러 테스트는 승인·반려의 입력 검증만 보고, 목록 조회는
 * 배포된 스택 위 인수 테스트에만 있었다 — 기본 빌드 밖이다.
 *
 * <p>접수 경로({@code receive})를 거치지 않고 원장을 직접 적재한다. {@code receive} 는
 * 자체등급분류 설정과 등급위원회 클라이언트에 따라 결과 상태가 갈려서, 조회 분기만
 * 독립적으로 관찰하려면 상태를 테스트가 직접 정하는 편이 낫다.
 *
 * <p>컨테이너를 다른 테스트와 공유하므로 목록에 남의 행이 섞인다. 그래서 크기가 아니라
 * <b>이 테스트가 넣은 행이 어느 목록에 들어오고 어느 목록에서 빠지는가</b>로 판정한다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class ReviewQueryTest {

    @Autowired
    ReviewService reviewService;
    @Autowired
    ReviewRequestRepository reviewRepository;

    /** 접수 직후 상태는 REQUESTED 다. 심사로 넘기지 않아 그대로 머문다. */
    private ReviewRequest requested() {
        return reviewRepository.save(ReviewRequest.received(
                1L, "GAME-" + UUID.randomUUID(), "로스트아크", 1001L, 39_000L, "KRW", false));
    }

    private ReviewRequest inReview() {
        ReviewRequest request = requested();
        request.startReview("BOARD-" + UUID.randomUUID());
        return reviewRepository.save(request);
    }

    private static List<Long> idsOf(List<ReviewRequest> requests) {
        return requests.stream().map(ReviewRequest::getId).toList();
    }

    @Test
    @DisplayName("상태를 주면 그 상태의 심의만 돌아온다")
    void filtersByStatus() {
        ReviewRequest waiting = requested();
        ReviewRequest reviewing = inReview();

        List<Long> ids = idsOf(reviewService.getRequests(ReviewStatus.REQUESTED));

        assertThat(ids).contains(waiting.getId());
        assertThat(ids).doesNotContain(reviewing.getId());
    }

    @Test
    @DisplayName("상태를 안 주면 상태와 무관하게 전부 돌아온다")
    void nullStatusReturnsEveryStatus() {
        ReviewRequest waiting = requested();
        ReviewRequest reviewing = inReview();

        List<Long> ids = idsOf(reviewService.getRequests(null));

        assertThat(ids).contains(waiting.getId(), reviewing.getId());
    }

    @Test
    @DisplayName("상태로 거른 목록은 접수 순서대로 나온다 — 화면이 다시 정렬하지 않아도 된다")
    void filteredListIsOrderedByIdAscending() {
        ReviewRequest first = requested();
        ReviewRequest second = requested();

        List<Long> ids = idsOf(reviewService.getRequests(ReviewStatus.REQUESTED));

        assertThat(ids).isSorted();
        assertThat(ids.indexOf(first.getId())).isLessThan(ids.indexOf(second.getId()));
    }
}
