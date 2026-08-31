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
 * 전체 재색인 오케스트레이션. 조율이 별도 클래스인 이유는 <b>트랜잭션 경계</b>와 <b>스로틀</b>
 * 둘이다. <b>트랜잭션을 열지 않는다</b> — 스로틀 대기가 들어오면 커넥션을 쥔 채 잠든다.
 * docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexFacade {

    private final ProductCommandService productCommandService;
    private final ReindexProperties properties;

    /**
     * 중복 기동 가드. <b>인스턴스 안에서만 유효하다</b> — 여러 대면 분산 락이 필요하다.
     * docs/code-notes.md
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
