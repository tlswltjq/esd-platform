package com.stove.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.event.payload.LicenseIssueFailedEvent;
import com.stove.common.event.payload.LicenseIssuedEvent;
import com.stove.common.event.payload.LicenseRevokedEvent;
import com.stove.common.event.payload.OrderCanceledEvent;
import com.stove.common.event.payload.OrderCreatedEvent;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.event.payload.ReviewRejectedEvent;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 이벤트는 서비스 간 <b>계약</b>이다. 여기서 깨지면 컴파일은 통과하고 운영에서 터진다 —
 * 발행 측과 수신 측이 다른 모듈이라 컴파일러가 불일치를 못 잡기 때문이다.
 *
 * <p>그래서 계약의 세 성질을 전수 검사한다.
 * <ol>
 *   <li><b>왕복 가능성</b> — 직렬화 후 역직렬화하면 같은 값이어야 한다</li>
 *   <li><b>메타데이터 완결성</b> — eventId/eventType/topic/partitionKey 가 비어 있으면
 *       멱등 처리와 순서 보장이 동시에 무너진다</li>
 *   <li><b>전방 호환</b> — 발행 측이 필드를 추가해도 아직 배포되지 않은 수신 측이 죽지 않아야 한다.
 *       무중단 배포가 성립하는 전제다</li>
 * </ol>
 */
class EventContractTest {

    /** 운영과 같은 관용 설정. 스프링 부트가 자동 구성하는 ObjectMapper 의 기본값을 흉내낸다. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static List<OrderLine> lines() {
        return List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 2));
    }

    /** 전 payload 카탈로그. 새 이벤트를 추가하면 여기에도 넣어야 계약 검사를 받는다. */
    static Stream<Named<DomainEvent>> allEvents() {
        return events().map(event -> Named.of(event.getClass().getSimpleName(), event));
    }

    private static Stream<DomainEvent> events() {
        return Stream.of(
                GameRegisteredEvent.of(1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", false),
                BuildUploadedEvent.of(1L, "GAME-001", "1.0.0", 1024L, "abc123", "s3://bucket/key"),
                ReviewApprovedEvent.of(1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", "ALL", false),
                ReviewRejectedEvent.of(1L, "GAME-001", "VIOLENCE", "선정성 기준 초과"),
                ProductChangedEvent.of(1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", "ON_SALE", "ALL"),
                OrderCreatedEvent.of("ORD-1", 42L, 60_000L, lines()),
                OrderCanceledEvent.of("ORD-1", 42L, "USER_CANCEL"),
                PaymentCompletedEvent.of(1L, "ORD-1", 42L, 60_000L, "CARD", lines()),
                PaymentCancelledEvent.of(1L, "ORD-1", 42L, 60_000L, "USER_REFUND"),
                LicenseIssuedEvent.of("ORD-1", 42L, List.of(1L, 2L)),
                LicenseRevokedEvent.of("ORD-1", 42L, List.of(1L, 2L), "USER_REFUND"),
                LicenseIssueFailedEvent.of("ORD-1", 42L, "재고 없음"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEvents")
    @DisplayName("모든 이벤트는 메타데이터가 완결되어 있다")
    void eventCarriesCompleteMetadata(DomainEvent event) {
        assertThat(event.eventId()).as("멱등 처리 키").isNotBlank();
        assertThat(event.eventType()).as("역직렬화 대상 선택 기준").isNotBlank();
        assertThat(event.topic()).as("발행 대상").isNotBlank();
        assertThat(event.partitionKey()).as("순서 보장 단위").isNotBlank();
        assertThat(event.occurredAt()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEvents")
    @DisplayName("모든 이벤트는 직렬화 왕복 후에도 같은 값이다")
    void eventSurvivesRoundTrip(DomainEvent event) throws Exception {
        String json = MAPPER.writeValueAsString(event);
        Object restored = MAPPER.readValue(json, event.getClass());

        assertThat(restored).isEqualTo(event);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEvents")
    @DisplayName("수신 측이 모르는 필드가 늘어나도 역직렬화된다 — 무중단 배포의 전제")
    void unknownFieldsAreTolerated(DomainEvent event) throws Exception {
        String json = MAPPER.writeValueAsString(event);
        // 발행 측이 새 필드를 추가한 상황을 흉내낸다
        String extended = json.substring(0, json.length() - 1) + ",\"brandNewField\":\"v2\"}";

        Object restored = MAPPER.readValue(extended, event.getClass());

        assertThat(restored).isEqualTo(event);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEvents")
    @DisplayName("토픽은 Topics 상수 중 하나다 — 오타 토픽으로 발행하면 아무도 못 받는다")
    void topicIsDeclaredConstant(DomainEvent event) {
        assertThat(event.topic()).isIn(
                Topics.STUDIO, Topics.REVIEW, Topics.CATALOG,
                Topics.ORDER, Topics.PAYMENT, Topics.LICENSE);
    }

    @Test
    @DisplayName("한 애그리거트의 이벤트는 같은 토픽으로 나간다 — 순서 보장의 전제")
    void sameAggregateSharesTopic() {
        assertThat(PaymentCompletedEvent.of(1L, "ORD-1", 42L, 1L, "CARD", lines()).topic())
                .isEqualTo(PaymentCancelledEvent.of(1L, "ORD-1", 42L, 1L, "R").topic());

        assertThat(LicenseIssuedEvent.of("ORD-1", 42L, List.of(1L)).topic())
                .isEqualTo(LicenseRevokedEvent.of("ORD-1", 42L, List.of(1L), "R").topic())
                .isEqualTo(LicenseIssueFailedEvent.of("ORD-1", 42L, "R").topic());
    }

    @Test
    @DisplayName("같은 주문의 이벤트는 같은 파티션 키를 쓴다 — 결제→지급→회수 순서가 보장되려면")
    void sameOrderSharesPartitionKey() {
        String orderNo = "ORD-20260802-0001";

        assertThat(Stream.of(
                        OrderCreatedEvent.of(orderNo, 42L, 1L, lines()).partitionKey(),
                        PaymentCompletedEvent.of(1L, orderNo, 42L, 1L, "CARD", lines()).partitionKey(),
                        PaymentCancelledEvent.of(1L, orderNo, 42L, 1L, "R").partitionKey(),
                        LicenseIssuedEvent.of(orderNo, 42L, List.of(1L)).partitionKey())
                .distinct().toList())
                .containsExactly(orderNo);
    }

    @Test
    @DisplayName("eventId 는 발행할 때마다 새로 생성된다 — 재사용하면 컨슈머가 두 번째를 중복으로 버린다")
    void eventIdIsUniquePerPublication() {
        String first = PaymentCompletedEvent.of(1L, "ORD-1", 42L, 1L, "CARD", lines()).eventId();
        String second = PaymentCompletedEvent.of(1L, "ORD-1", 42L, 1L, "CARD", lines()).eventId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("금액 계산은 단가 × 수량이다")
    void lineAmountIsUnitPriceTimesQuantity() {
        assertThat(new OrderLine(1L, "게임 A", 1001L, 30_000L, 3).lineAmount()).isEqualTo(90_000L);
    }
}
