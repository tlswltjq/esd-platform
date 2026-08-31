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
 * DLT 를 들여다보고 되돌리는 운영 API. 게이트웨이가 라우팅하지 않는다.
 * JPA 를 요구하지 않는 {@code common:kafka} 에 있는 이유는 docs/code-notes.md
 */
@RestController
@RequestMapping("/api/v1/ops/dlt")
@RequiredArgsConstructor
public class DltOpsController {

    /** 한 번에 가져올 양의 천장. <b>운영 API 는 장애 중에 불린다.</b> */
    private static final int MAX_BATCH = 500;

    private final DltOpsService dltOps;

    /** 무엇이 왜 실패했는지 본다. <b>커밋하지 않는다.</b> */
    @GetMapping
    public ApiResponse<List<DltRecordResponse>> peek(@RequestParam String topic,
                                                     @RequestParam(defaultValue = "20") int max) {
        return ApiResponse.ok(dltOps.peek(topic, capped(max)));
    }

    /** 원본 토픽으로 되돌린다. <b>원인을 먼저 고쳐야 한다</b> — {@link #peek} 로 보고 판단한다. */
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
