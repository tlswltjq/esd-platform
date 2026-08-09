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
 * 발행을 포기한 Outbox 이벤트를 되살리는 운영 API.
 *
 * <p>이 컨트롤러가 공용 라이브러리에 있는 이유는 <b>7개 서비스가 같은 표(outbox_event)를
 * 쓰기 때문</b>이다. 서비스마다 복제하면 7벌이 서서히 갈라진다.
 * 수신 측 실패(DLT)는 Outbox 가 없는 서비스에도 있으므로 {@code common:kafka} 가 따로 다룬다.
 *
 * <p><b>게이트웨이가 라우팅하지 않는다.</b> 이 경로는 라우팅 표에 없으므로 외부에서 도달할 수 없고,
 * 서비스 포트로 직접(내부망에서) 부른다. 실운영이라면 내부망 전용 게이트웨이가 맡을 자리다 —
 * 게이트웨이 설정의 "사내 운영툴" 주석이 가리키는 그 분리다.
 *
 * <p>화면은 따로 만들지 않았다. 이 API 는 각 서비스의 Swagger UI 에 그대로 뜨고,
 * 목록 조회와 버튼 두 개가 전부인 도구에는 그것으로 충분하다.
 *
 * <p><b>{@code @RestController} 는 {@code @Component} 를 품고 있다.</b> 이 패키지를 컴포넌트 스캔하는
 * 컨텍스트가 있으면 자동 구성을 거치지 않고 등록되어, {@code stove.messaging.ops.enabled=false}
 * 스위치가 조용히 무력해진다. 실제로 이 모듈의 테스트 앱이 그 경우라 컨텍스트가 깨졌고,
 * 거기서는 스캔 제외로 막았다({@code OutboxQueryTestApplication}).
 * 서비스들은 자기 패키지만 스캔하므로 애초에 겹치지 않는다.
 *
 * <p>스캔되지 않는 형태({@code @RequestMapping} + {@code @ResponseBody})도 요청 처리에는 문제가 없지만
 * <b>springdoc 이 문서화하지 않는다</b> — 확인해 봤고, 명세에서 이 API 가 통째로 빠졌다.
 * 운영 도구를 Swagger UI 로 쓰는 것이 화면을 안 만든 이유이므로, 문서에서 사라지면 목적이 무너진다.
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
