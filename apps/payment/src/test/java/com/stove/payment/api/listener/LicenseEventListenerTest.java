package com.stove.payment.api.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.LicenseIssueFailedEvent;
import com.stove.common.event.payload.LicenseIssuedEvent;
import com.stove.common.test.EventRecords;
import com.stove.payment.api.application.RefundFacade;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Saga 보상의 트리거. <b>여기가 돈을 되돌리는 유일한 자동 경로</b>다.
 *
 * <p>그래서 분기가 좁아야 한다. {@code LicenseIssueFailed} 하나에만 반응하며,
 * 정상 지급({@code LicenseIssued})에 반응하면 성공한 결제를 환불하게 된다.
 * D-002 는 "일시 장애 한 번에 보상이 발동"한 결함이었고, 그 수정으로
 * 보상 트리거가 리스너에서 recoverer 로 옮겨졌다 — 이 리스너는 그 뒤 단계다.
 */
class LicenseEventListenerTest {

    private final RefundFacade refundFacade = mock(RefundFacade.class);
    private final LicenseEventListener listener =
            new LicenseEventListener(refundFacade, EventRecords.OBJECT_MAPPER);

    private static final LicenseIssueFailedEvent FAILED =
            LicenseIssueFailedEvent.of("ORD-1", 42L, "재고 없음");

    @Test
    @DisplayName("지급 실패는 보상 환불로 이어지고 사유가 접두사와 함께 실린다")
    void issueFailureTriggersCompensation() {
        listener.onLicenseEvent(EventRecords.of(Topics.LICENSE, FAILED));

        // 접두사는 환불 사유를 나중에 분류할 때 쓴다 — 사용자 요청 환불과 구분되어야 한다.
        verify(refundFacade).compensate(anyString(), eq(EventType.LICENSE_ISSUE_FAILED),
                eq("ORD-1"), eq("LICENSE_ISSUE_FAILED:재고 없음"));
    }

    @Test
    @DisplayName("정상 지급에는 반응하지 않는다 — 성공한 결제를 환불하면 안 된다")
    void successfulIssueDoesNotCompensate() {
        listener.onLicenseEvent(EventRecords.of(Topics.LICENSE,
                LicenseIssuedEvent.of("ORD-1", 42L, List.of(1L))));

        verifyNoInteractions(refundFacade);
    }

    @Test
    @DisplayName("관심 없는 eventType 은 아무 일도 하지 않는다")
    void unrelatedEventTypeIsIgnored() {
        listener.onLicenseEvent(EventRecords.ofUnrelatedType(Topics.LICENSE));

        verifyNoInteractions(refundFacade);
    }

    @Test
    @DisplayName("보상 중 일시 장애는 예외로 전파된다 — 삼키면 환불이 누락된다")
    void propagatesTransientFailure() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(refundFacade).compensate(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> listener.onLicenseEvent(EventRecords.of(Topics.LICENSE, FAILED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
