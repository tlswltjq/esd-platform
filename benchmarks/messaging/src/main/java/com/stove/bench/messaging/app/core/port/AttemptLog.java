package com.stove.bench.messaging.app.core.port;

import com.stove.bench.messaging.app.core.domain.Attempt;

/** 수신 시도 기록. 처리 경로에서 동기 I/O를 하지 않는다 — 구현은 메모리에 쌓고 따로 내보낸다(E13). */
public interface AttemptLog {

    void record(Attempt attempt);
}
