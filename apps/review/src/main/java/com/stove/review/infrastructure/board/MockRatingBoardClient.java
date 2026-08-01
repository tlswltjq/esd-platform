package com.stove.review.infrastructure.board;

import com.stove.review.core.port.RatingBoardClient;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 로컬용 게임위 접수 스텁. */
@Slf4j
@Component
public class MockRatingBoardClient implements RatingBoardClient {

    private final AtomicLong sequence = new AtomicLong(1);

    @Override
    public String submit(String productCode, String title, Long sellerId) {
        String ticketId = "GRAC-%s-%05d".formatted(LocalDate.now().getYear(), sequence.getAndIncrement());
        log.info("[MOCK 게임위] 심의 접수 productCode={} title={} → ticket={}", productCode, title, ticketId);
        return ticketId;
    }
}
