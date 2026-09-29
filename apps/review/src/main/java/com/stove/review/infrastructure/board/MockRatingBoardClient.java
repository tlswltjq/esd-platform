package com.stove.review.infrastructure.board;

import com.stove.review.core.domain.RatingBoardSubmission;
import com.stove.review.core.port.RatingBoardClient;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 로컬용 게임위 접수 스텁. */
@Slf4j
@Profile("!prod")
@Component
public class MockRatingBoardClient implements RatingBoardClient {

    private final AtomicLong sequence = new AtomicLong(1);
    private final ConcurrentMap<Long, String> submissionTickets = new ConcurrentHashMap<>();

    @Override
    public String submitLegacy(String productCode, String title, Long sellerId) {
        String ticketId = nextTicket();
        log.info("[MOCK 게임위] 심의 접수 productCode={} title={} → ticket={}", productCode, title, ticketId);
        return ticketId;
    }

    @Override
    public String submit(RatingBoardSubmission submission) {
        String ticketId = submissionTickets.computeIfAbsent(submission.submissionId(), ignored -> nextTicket());
        log.info("[MOCK 게임위] 제출 스냅샷 접수 submissionId={} productCode={} buildId={} target={} → ticket={}",
                submission.submissionId(), submission.productCode(), submission.buildId(),
                submission.targetRatingCode(), ticketId);
        return ticketId;
    }

    private String nextTicket() {
        return "GRAC-%s-%05d".formatted(LocalDate.now().getYear(), sequence.getAndIncrement());
    }
}
