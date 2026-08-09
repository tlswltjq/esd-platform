package com.stove.common.kafka.ops;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DLT 를 들여다보고 되돌리는 운영 API.
 *
 * <p>수신은 하지만 발행은 하지 않는 서비스(store·download)도 이 API 를 갖는다 —
 * <b>DLT 로 보낼 수 있으면 되돌릴 수도 있어야 한다.</b> 그래서 Outbox 운영 API 와 달리
 * 이쪽은 JPA 를 요구하지 않는 {@code common:kafka} 에 둔다.
 *
 * <p>게이트웨이가 라우팅하지 않는다. 서비스 포트로 직접(내부망에서) 부른다.
 */
@RestController
@RequestMapping("/api/v1/ops/dlt")
@RequiredArgsConstructor
public class DltOpsController {

    /**
     * 한 번에 가져올 수 있는 양의 천장.
     *
     * <p>운영 API 는 장애 중에 불린다 — 그때 {@code max=1000000} 이 들어오면
     * 이미 아픈 브로커와 이 서비스의 힙을 같이 끌어내린다.
     */
    private static final int MAX_BATCH = 500;

    private final DltOpsService dltOps;

    /**
     * 무엇이 왜 실패했는지 본다. <b>커밋하지 않으므로</b> 몇 번을 봐도 상태가 변하지 않고,
     * 여기 보이는 것이 곧 재투입이 다음에 처리할 것이다.
     */
    @GetMapping
    public ApiResponse<List<DltRecordResponse>> peek(@RequestParam String topic,
                                                     @RequestParam(defaultValue = "20") int max) {
        return ApiResponse.ok(dltOps.peek(topic, capped(max)));
    }

    /**
     * 원본 토픽으로 되돌린다.
     *
     * <p>원인을 고치지 않고 부르면 같은 실패를 반복해 DLT 로 돌아온다 —
     * 도구가 대신 판단해 줄 수 없어서 {@link #peek} 로 먼저 보게 되어 있다.
     */
    @PostMapping("/replay")
    public ApiResponse<ReplayedCount> replay(@RequestParam String topic,
                                             @RequestParam(defaultValue = "100") int max) {
        return ApiResponse.ok(new ReplayedCount(dltOps.replay(topic, capped(max))));
    }

    private static int capped(int max) {
        if (max < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "max 는 1 이상이어야 한다");
        }
        return Math.min(max, MAX_BATCH);
    }

    /** @param replayed 원본 토픽으로 되돌린 건수 */
    public record ReplayedCount(int replayed) {
    }
}
