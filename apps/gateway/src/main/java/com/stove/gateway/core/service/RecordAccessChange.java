package com.stove.gateway.core.service;

import com.stove.gateway.core.domain.AccessChange;
import com.stove.gateway.core.port.AccessBaselines;
import com.stove.gateway.core.port.ProcessingLag;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 계정 접근 변경 사실을 subject별 거절 기준으로 남긴다(결정 180·225, R5.6). 이 뒤로 {@code iat}가 기준보다 이른 Bearer는
 * 거절된다. 기준은 더 늦은 {@code changedAt}만 덮으므로 순서·중복과 무관하게 같은 결과다.
 */
@Service
@RequiredArgsConstructor
public class RecordAccessChange {

    /** 소비자 그룹. 웹 BFF(세션 삭제)와 다른 그룹으로 같은 사실을 받는다(결정 225). */
    public static final String CONSUMER_GROUP = "gateway-account-access";

    private final AccessBaselines baselines;
    private final ProcessingLag lag;

    public void execute(AccessChange change) {
        lag.started(change.changedAt());
        baselines.raise(change);
        lag.finished();
    }
}
