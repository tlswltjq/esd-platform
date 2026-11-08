package com.stove.common.messaging.ops;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SENT Outbox 이벤트를 같은 ID 로 다시 내보내는 운영 API([결정 129]). 게이트웨이가 라우팅하지 않는다.
 * {@link OutboxOpsController} 와 같은 스위치({@code stove.messaging.ops.enabled})를 따르고,
 * 재발행 기록 표를 가진 모듈만 {@code stove.messaging.ops.republish-enabled=true} 로 켠다.
 */
@RestController
@RequestMapping("/api/v1/ops/outbox")
@RequiredArgsConstructor
public class OutboxRepublishController {

    private final OutboxRepublishService republish;

    /** 한 건을 원래 {@code event_id}·본문·키로 발행 대기에 돌린다. 200 은 접수이며 업무 완료가 아니다. */
    @PostMapping("/{eventId}/republish")
    public ApiResponse<OutboxRepublishResponse> republish(@CurrentSubject String operator,
                                                          @PathVariable String eventId,
                                                          @RequestBody RepublishRequest body) {
        return ApiResponse.ok(republish.republish(eventId, operator, body == null ? null : body.reason()));
    }

    /** @param reason 재발행 사유(1~500자). 운영 기록의 조사 번호·확인 결과를 적는다 */
    public record RepublishRequest(String reason) {
    }
}
