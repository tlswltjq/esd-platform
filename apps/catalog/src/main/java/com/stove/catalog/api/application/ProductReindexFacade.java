package com.stove.catalog.api.application;

import com.stove.catalog.config.ReindexProperties;
import com.stove.catalog.core.domain.ReindexPage;
import com.stove.catalog.core.service.ProductCommandService;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 전체 재색인 오케스트레이션.
 *
 * <p>여기서 조율이 필요한 이유는 <b>트랜잭션 경계</b> 때문이다. {@code OutboxRecorder} 가
 * {@code MANDATORY} 라 발행은 트랜잭션 안이어야 하는데, 전체를 한 트랜잭션으로 묶으면
 * 상품 테이블 전량이 메모리에 올라오고 막판 실패가 전량 롤백이 된다.
 * 페이지마다 커밋하려면 반복이 트랜잭션 <b>밖</b>에 있어야 하고,
 * 자기 호출은 프록시를 타지 않으므로 그 반복은 다른 클래스에 있어야 한다.
 *
 * <p><b>스로틀이 두 번째 이유다.</b> Outbox 릴레이는 전 서비스가 공유하는 자원이고
 * 실측 처리량이 약 480 events/s 다(docs/performance.md). 재색인이 10만 건을 한꺼번에
 * 밀어 넣으면 약 3.5분간 릴레이가 재색인으로 포화되고, 그동안 정상 판매 상태 변경 이벤트가
 * 전부 그 뒤에 줄을 선다 — 자기가 만든 적체에 자기 서비스가 밀린다.
 *
 * <p>트랜잭션을 열지 않는다. 스로틀 대기가 트랜잭션 안으로 들어오면 커넥션을 쥔 채 잠든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexFacade {

    private final ProductCommandService productCommandService;
    private final ReindexProperties properties;

    /**
     * 중복 기동 가드.
     *
     * <p>트리거가 HTTP 라 운영자가 두 번 누르거나 두 명이 동시에 누를 수 있다. 그러면 같은 일이
     * 두 배로 돌아 릴레이 적체도 두 배가 된다. 색인 자체는 멱등이라 결과는 같지만,
     * <b>그 사이 정상 이벤트가 받는 지연은 두 배</b>다.
     *
     * <p>인스턴스 안에서만 유효한 가드다. 여러 대로 늘리면 정산 배치처럼 분산 락이 필요하다 —
     * 다만 재색인은 금전 처리가 아니고 결과가 멱등이라 우선순위가 다르다.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 전체 재색인. 신규 색인 구축이나 store 색인 유실 시 운영툴에서 호출한다.
     *
     * @return 발행한 이벤트 수
     * @throws BusinessException 이미 재색인이 돌고 있으면
     */
    public int reindexAll() {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.CONFLICT, "재색인이 이미 진행 중입니다");
        }
        try {
            return runReindex();
        } finally {
            running.set(false);
        }
    }

    private int runReindex() {
        int total = 0;
        long cursor = 0L;

        while (true) {
            ReindexPage page = productCommandService.republishFrom(cursor, properties.pageSize());
            total += page.published();
            cursor = page.lastId();

            if (!page.hasNext()) {
                break;
            }
            throttle();
        }

        log.info("재색인 완료 {}건", total);
        return total;
    }

    /** 페이지 사이 대기. 릴레이가 재색인만으로 채워지지 않게 한다. */
    private void throttle() {
        try {
            Thread.sleep(properties.pageInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재색인이 중단됐다", e);
        }
    }
}
