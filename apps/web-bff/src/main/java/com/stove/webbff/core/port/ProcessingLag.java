package com.stove.webbff.core.port;

import java.time.Instant;

/** 계정 접근 변경 소비의 지연(아직 처리하지 못한 사실의 나이, R5.8). */
public interface ProcessingLag {

    /** 이 시각에 일어난 사실을 처리하기 시작한다. 끝내지 못하면 그 나이가 계속 늘어난다. */
    void started(Instant occurredAt);

    /** 처리를 마쳤다. 밀린 사실이 없으면 지연은 0이다. */
    void finished();
}
