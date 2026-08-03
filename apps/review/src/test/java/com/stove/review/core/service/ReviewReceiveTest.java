package com.stove.review.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.test.InfraContainers;
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
 * 심의 접수 — studio 의 {@code GameRegistered} 를 받는 경로.
 *
 * <p>이 서비스에는 그동안 테스트가 없었다. 리스너 테스트가 {@code ReviewService} 를 mock 으로
 * 세워 뒀기 때문에 <b>구현이 한 번도 실행되지 않은 상태</b>였고, 그래서 멱등 가드도 미검증이었다.
 *
 * <p>접수는 컨슈머 경로다. 여기서 던진 예외는 리스너 밖으로 나가 그 파티션을 멈춘다 —
 * 그래서 "무엇을 저장하는가"만큼 <b>무엇을 던지지 않는가</b>가 중요하다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class ReviewReceiveTest {

    @Autowired
    ReviewService reviewService;
    @Autowired
    ReviewRequestRepository reviewRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    private static String uniqueProductCode() {
        return "GAME-" + UUID.randomUUID();
    }

    private static GameRegisteredEvent registration(String productCode, boolean selfRated) {
        return GameRegisteredEvent.of(1L, productCode, "로스트아크", 1001L, 39_000L, "KRW", selfRated);
    }

    private void receive(GameRegisteredEvent event) {
        reviewService.receive(UUID.randomUUID().toString(), EventType.GAME_REGISTERED, event);
    }

    private ReviewRequest find(String productCode) {
        return reviewRepository.findByProductCode(productCode).orElseThrow();
    }

    private List<OutboxEvent> outboxFor(String productCode) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> productCode.equals(e.getPartitionKey()))
                .toList();
    }

    @Test
    @DisplayName("자체등급분류는 게임위를 거치지 않고 접수 즉시 승인된다")
    void selfRatedIsApprovedOnArrival() {
        String productCode = uniqueProductCode();

        receive(registration(productCode, true));

        ReviewRequest request = find(productCode);
        assertThat(request.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(request.getRatingCode()).isEqualTo("ALL");
        // 게임위 접수번호가 없다는 것이 자체등급분류 건의 표식이다
        assertThat(request.getBoardTicketId()).isNull();

        List<OutboxEvent> published = outboxFor(productCode);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).getEventType()).isEqualTo(EventType.REVIEW_APPROVED);
    }

    @Test
    @DisplayName("자체등급분류가 아니면 게임위 접수번호를 받아 심사 중으로 둔다")
    void externalRatingWaitsForTheBoard() {
        String productCode = uniqueProductCode();

        receive(registration(productCode, false));

        ReviewRequest request = find(productCode);
        assertThat(request.getStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
        assertThat(request.getBoardTicketId()).startsWith("GRAC-");
        // 승인 이벤트는 담당자가 승인 API 를 부른 뒤에 나간다
        assertThat(outboxFor(productCode)).isEmpty();
    }

    @Test
    @DisplayName("같은 이벤트를 다시 받아도 접수는 한 번만 일어난다")
    void redeliveryIsIgnored() {
        String productCode = uniqueProductCode();
        GameRegisteredEvent event = registration(productCode, true);
        String eventId = UUID.randomUUID().toString();

        reviewService.receive(eventId, EventType.GAME_REGISTERED, event);
        reviewService.receive(eventId, EventType.GAME_REGISTERED, event);

        assertThat(reviewRepository.findByProductCode(productCode)).isPresent();
        // 두 번 발행되면 catalog 가 상품을 두 번 만들고 studio 상태도 두 번 바뀐다
        assertThat(outboxFor(productCode)).hasSize(1);
    }

    @Test
    @DisplayName("반려된 건의 재신청은 같은 레코드를 되돌린다 — 이력을 유지한다")
    void resubmitAfterRejectionReopensTheSameRecord() {
        String productCode = uniqueProductCode();
        receive(registration(productCode, false));
        Long reviewId = find(productCode).getId();
        reviewService.reject(reviewId, "DOC", "자료 미비");

        receive(registration(productCode, false));

        ReviewRequest request = find(productCode);
        assertThat(request.getId()).isEqualTo(reviewId);
        assertThat(request.getStatus()).isEqualTo(ReviewStatus.IN_REVIEW);
        assertThat(reviewRepository.findAll().stream()
                .filter(r -> productCode.equals(r.getProductCode()))).hasSize(1);
    }

    @Test
    @DisplayName("[D-016] 심사 중인 건에 재신청이 들어와도 컨슈머가 멈추지 않는다")
    void resubmitOnLiveRequestMustNotStallTheConsumer() {
        String productCode = uniqueProductCode();
        receive(registration(productCode, false));
        assertThat(find(productCode).getStatus()).isEqualTo(ReviewStatus.IN_REVIEW);

        // 수정 전에는 상태와 무관하게 reopen() → transitTo(REQUESTED) 를 불렀다.
        // IN_REVIEW → REQUESTED 는 금지 전이라 BusinessException 이 리스너 밖으로 나갔다.
        //
        // 멱등 가드는 같은 eventId 만 막는다. 새 eventId 를 단 정상 재신청이 이 상태를 만나면
        // studio 토픽의 해당 파티션이 무한 재시도에 빠지고, 심의 상태는 재시도로 바뀌지 않으므로
        // 사람이 개입할 때까지 뒤에 줄 선 모든 게임의 심의가 함께 멈췄다.
        assertThatCode(() -> receive(registration(productCode, false)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[D-016] 승인이 끝난 건에 재신청이 들어와도 컨슈머가 멈추지 않는다")
    void resubmitOnApprovedRequestMustNotStallTheConsumer() {
        String productCode = uniqueProductCode();
        receive(registration(productCode, true));
        assertThat(find(productCode).getStatus()).isEqualTo(ReviewStatus.APPROVED);

        // APPROVED 는 종착 상태라 어떤 전이도 허용하지 않는다 — 위와 같은 이유로 파티션이 멈춘다.
        assertThatCode(() -> receive(registration(productCode, true)))
                .doesNotThrowAnyException();

        // 그리고 이미 끝난 심의를 되돌리지도 않는다
        assertThat(find(productCode).getStatus()).isEqualTo(ReviewStatus.APPROVED);
    }
}
