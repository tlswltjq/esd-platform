package com.stove.common.messaging.quarantine;

import java.util.List;

/** 보관된 메시지의 상태. {@code HELD}·{@code REPLAYING} 이 하나라도 있으면 그 키는 멈춰 있다. */
public enum QuarantineStatus {
    /** 처리하지 않고 보관 중. 그 (그룹, 토픽, 키)를 멈춘다. */
    HELD,
    /** 운영자 재투입이 이 행을 점유해 처리 중. 키는 계속 멈춰 있다. */
    REPLAYING,
    /** 운영자가 다시 넣어 처리됐다. 기록으로 남긴다. */
    REPLAYED;

    /** 키를 멈추는 상태들. */
    public static final List<QuarantineStatus> STOPPING = List.of(HELD, REPLAYING);
}
