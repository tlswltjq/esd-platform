package com.stove.gateway.adapter.redis;

import com.stove.gateway.core.domain.AccessChange;
import com.stove.gateway.core.port.AccessBaselines;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * subject별 거절 기준을 Redis에 둔다(R5.6, 결정 225). 키 {@code stove:gateway:access-baseline:<subject>} = 가장 늦은
 * {@code changedAt}(epoch 밀리초), 멱등 키 {@code stove:gateway:access-event:<eventId>}. 둘 다 같은 수명({@code ttl})이고, 그
 * 수명은 access token 최대 수명 이상이어야 한다 — 기준이 먼저 사라지면 이전 토큰이 남은 수명 동안 다시 통한다.
 */
public class RedisAccessBaselines implements AccessBaselines {

    static final String BASELINE = "stove:gateway:access-baseline:";
    static final String EVENT = "stove:gateway:access-event:";

    /** 처음 보는 eventId면 기준을 더 늦은 값으로만 올리고 수명을 다시 건다. 이미 본 eventId면 0. */
    private static final RedisScript<Long> RAISE = RedisScript.of("""
            if not redis.call('set', KEYS[2], '1', 'NX', 'PX', ARGV[2]) then return 0 end
            local current = redis.call('get', KEYS[1])
            if (not current) or tonumber(current) < tonumber(ARGV[1]) then
              redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final Duration ttl;
    private final Duration timeout;

    public RedisAccessBaselines(ReactiveStringRedisTemplate redis, Duration ttl, Duration timeout) {
        this.redis = redis;
        this.ttl = ttl;
        this.timeout = timeout;
    }

    @Override
    public boolean raise(AccessChange change) {
        // 소비자 스레드(블로킹)에서 부른다. 실패는 예외로 올려 소비자가 성공할 때까지 다시 시도한다(결정 226).
        Long applied = redis.execute(RAISE, List.of(BASELINE + change.subject(), EVENT + change.eventId()),
                        List.of(Long.toString(change.changedAt().toEpochMilli()), Long.toString(ttl.toMillis())))
                .next().block(timeout);
        return applied != null && applied == 1L;
    }

    /** 그 subject의 거절 기준. 없으면 빈 값. Redis에 닿지 못하면 오류를 그대로 낸다(우회하지 않는다, R6.3). */
    public Mono<Instant> baseline(String subject) {
        return redis.opsForValue().get(BASELINE + subject).map(value -> Instant.ofEpochMilli(Long.parseLong(value)));
    }
}
