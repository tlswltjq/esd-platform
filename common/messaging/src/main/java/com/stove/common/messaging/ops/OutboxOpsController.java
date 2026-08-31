package com.stove.common.messaging.ops;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발행을 포기한 Outbox 이벤트를 되살리는 운영 API. 게이트웨이가 라우팅하지 않는다.
 *
 * <p><b>{@code @RestController} 를 다른 형태로 바꾸지 말 것</b> — 스캔되지 않는 형태로 두면
 * springdoc 이 문서화하지 않아 Swagger UI 로 쓰는 목적이 무너진다. 대신 이 패키지를 스캔하는
 * 컨텍스트에서는 {@code enabled=false} 스위치가 무력해진다. docs/code-notes.md
 */
@RestController
@RequestMapping("/api/v1/ops/outbox")
@RequiredArgsConstructor
public class OutboxOpsController {

    private final OutboxOpsService outboxOps;

    /** 발행을 포기한(DEAD) 이벤트. 정상이라면 빈 목록이다. */
    @GetMapping("/dead")
    public ApiResponse<List<DeadEventResponse>> deadEvents() {
        return ApiResponse.ok(outboxOps.deadEvents());
    }

    /** 한 건을 발행 대기로 되돌린다. 원인을 먼저 제거한 뒤에 부르는 것이 전제다. */
    @PostMapping("/dead/{eventId}/requeue")
    public ApiResponse<Void> requeue(@PathVariable String eventId) {
        if (!outboxOps.requeue(eventId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "DEAD 상태의 이벤트가 아니다: " + eventId);
        }
        return ApiResponse.ok();
    }

    /** 브로커 장애처럼 원인이 하나였던 경우를 위한 일괄 회수. */
    @PostMapping("/dead/requeue-all")
    public ApiResponse<RequeuedCount> requeueAll() {
        return ApiResponse.ok(new RequeuedCount(outboxOps.requeueAll()));
    }

    /** @param requeued 발행 대기로 되돌린 건수 */
    public record RequeuedCount(int requeued) {
    }
}
