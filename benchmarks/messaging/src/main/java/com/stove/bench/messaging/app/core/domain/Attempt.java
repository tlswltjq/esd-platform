package com.stove.bench.messaging.app.core.domain;

/**
 * 수신 시도 한 번(E13 "층 2 수신 시도 기록"). 업무 트랜잭션 밖에서 남긴다 — 실패한 시도는 시험 표·Inbox가 롤백돼 DB에 흔적이 없다.
 *
 * @param eventId 메시지 ID.
 * @param attempt 이 프로세스 안의 시도 번호(1부터). 재시작하면 1부터 다시 센다.
 * @param partition 받은 파티션.
 * @param offset 받은 offset.
 * @param receivedAtMicros 리스너가 받은 벽시계(epoch µs).
 * @param finishedAtMicros 처리(커밋 또는 롤백)가 끝난 벽시계(epoch µs).
 * @param outcome 결과.
 * @param error 실패면 원인 예외 클래스 이름, 아니면 빈 문자열.
 */
public record Attempt(String eventId, int attempt, int partition, long offset, long receivedAtMicros,
                      long finishedAtMicros, Outcome outcome, String error) {

    /** 격리·DLT는 오류 처리기가 시도 뒤에 정하므로 여기 없다. 최종 상태는 격리 표·DLT로 판정한다. */
    public enum Outcome {
        /** 시험 표에 반영하고 커밋했다. */
        APPLIED,
        /** Inbox가 이미 처리한 메시지라 반영하지 않았다(재전달). */
        DUPLICATE,
        /** 처리 중 예외로 롤백했다. 오류 처리기가 재시도하거나 격리한다. */
        FAILED
    }

    public Attempt {
        if (eventId == null || outcome == null || error == null) {
            throw new IllegalArgumentException("시도 기록에 빈 값이 있다");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("시도 번호는 1부터다: " + attempt);
        }
    }

    /**
     * CSV 한 줄. 열 순서는 {@link #CSV_HEADER}. 값에 쉼표가 들어가지 않는다(UUID·숫자·예외 클래스 이름).
     * 마지막 칸 {@link #END}는 줄이 끝까지 쓰였다는 표시다 — 쓰기가 마지막 칸 중간에서 끊겨도 끊긴 줄로 가려낸다(PR #253 재리뷰 R6).
     */
    public String toCsv() {
        return String.join(",", eventId, Integer.toString(attempt), Integer.toString(partition), Long.toString(offset),
                Long.toString(receivedAtMicros), Long.toString(finishedAtMicros), outcome.name(), error, END);
    }

    public static final String END = "#";
    public static final String CSV_HEADER = "event_id,attempt,partition,offset,received_us,finished_us,outcome,error,end";
}
