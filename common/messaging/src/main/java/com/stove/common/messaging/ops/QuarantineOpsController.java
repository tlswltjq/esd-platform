package com.stove.common.messaging.ops;

import com.stove.common.messaging.quarantine.QuarantineReplay;
import com.stove.common.messaging.quarantine.QuarantineReplay.ReplayResult;
import com.stove.common.messaging.quarantine.StoppedKey;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 멈춘 소비 키를 보고 원래 순서대로 다시 넣는 운영 API(결정 155·206). 게이트웨이가 라우팅하지 않고 ADMIN 만 쓴다.
 * 브로커와 무관하다 — 보관은 이 모듈 DB 에, 재투입은 이 앱의 리스너로 직접 한다.
 */
@RestController
@RequestMapping("/api/v1/ops/quarantine")
@RequiredArgsConstructor
public class QuarantineOpsController {

    private final QuarantineReplay replay;

    /** 멈춘 키. 정상이라면 빈 목록이다. */
    @GetMapping
    public ApiResponse<List<StoppedKey>> stoppedKeys() {
        return ApiResponse.ok(replay.stoppedKeys());
    }

    /** 한 키를 원래 순서대로 다시 넣는다. 원인을 먼저 제거한 뒤에 부르는 것이 전제다. */
    @PostMapping("/replay")
    public ApiResponse<ReplayResult> replay(@RequestBody ReplayRequest request) {
        if (request == null || blank(request.consumerGroup()) || blank(request.topic()) || request.key() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "consumerGroup·topic·key 가 필요하다");
        }
        try {
            return ApiResponse.ok(replay.replay(request.consumerGroup(), request.topic(), request.key()));
        } catch (IllegalArgumentException unknownGroup) {
            throw new BusinessException(ErrorCode.NOT_FOUND, unknownGroup.getMessage());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** @param key 순서 키. 키 없이 보관된 메시지는 빈 문자열 */
    public record ReplayRequest(String consumerGroup, String topic, String key) {
    }
}
