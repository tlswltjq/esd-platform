package com.stove.common.messaging.quarantine;

/** 보관된 메시지의 상태. {@code HELD} 가 하나라도 있으면 그 키는 멈춰 있다. */
public enum QuarantineStatus {
    /** 처리하지 않고 보관 중. 그 (그룹, 토픽, 키)를 멈춘다. */
    HELD,
    /** 운영자가 다시 넣어 처리됐다. 기록으로 남긴다. */
    REPLAYED
}
