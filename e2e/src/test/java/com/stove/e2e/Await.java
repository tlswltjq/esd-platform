package com.stove.e2e;

import com.stove.e2e.E2eClient.Response;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.awaitility.core.ConditionTimeoutException;

/**
 * 이벤트 전파를 기다리는 자리.
 *
 * <p><b>고정 sleep 이 아니라 조건을 기다린다.</b> 주문 하나가 지급까지 가는 데 Outbox 폴링
 * 릴레이(기본 1초)가 두 번, Kafka 홉이 두 번 낀다. 걸리는 시간이 실행마다 다르므로 고정 대기는
 * 짧으면 간헐 실패고 길면 전체가 느려진다 — 셸이 {@code await} 함수를 따로 둔 이유와 같다.
 *
 * <p>제한은 60초다. 셸이 쓰던 값을 그대로 가져왔다. 이 값을 넘겨 실패한 것은 "느렸다" 가 아니라
 * <b>전파가 끊겼다</b>는 뜻이다 — 실측에서 전 구간이 43초에 끝나고 한 구간은 대개 0~2초에 성립한다.
 */
public final class Await {

    private static final Duration LIMIT = Duration.ofSeconds(60);

    private Await() {
    }

    /**
     * 조건이 성립할 때까지 기다리고, 실패하면 <b>마지막으로 본 응답</b>을 함께 남긴다.
     *
     * <p>Awaitility 의 기본 실패 메시지는 "조건이 성립하지 않았다" 까지만 말한다. 여기서는 그 시점의
     * 실물이 필요하다 — 라이선스가 아직 없는 것과 라이브러리가 500 을 내는 것은 대응이 다르고,
     * 60초 뒤에 그 둘을 구분하려면 로그를 다시 뒤져야 한다.
     */
    public static void untilResponse(String what, Supplier<Response> call, Predicate<Response> condition) {
        Response[] last = new Response[1];
        try {
            poll().until(() -> {
                last[0] = call.get();
                return condition.test(last[0]);
            });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(
                    "%s — %d초 안에 성립하지 않았다. 마지막 응답: %s".formatted(what, LIMIT.toSeconds(), last[0]), e);
        }
    }

    /**
     * {@code pollDelay} 를 0 으로 둔다 — 기본값(100ms)이면 이미 도착해 있는 것도 한 번 쉬고 본다.
     * 판정이 40건 넘게 쌓이면 그 쉬는 시간이 그대로 실행 시간이 된다.
     */
    private static ConditionFactory poll() {
        return Awaitility.await()
                .atMost(LIMIT)
                .pollInterval(Duration.ofSeconds(1))
                .pollDelay(Duration.ZERO);
    }
}
