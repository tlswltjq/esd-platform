package com.stove.webbff.adapter.redis;

import com.stove.webbff.core.port.SubjectSessions;
import java.time.Duration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * subject 색인({@code stove:bff:subject-tokens:<subject>})으로 그 subject의 토큰 기록을 모두 지운다. 색인은 토큰 기록을 쓸 때
 * {@code RedisAuthorizedClients}가 채운다. 토큰 기록이 없으면 다음 요청은 401이고 그 세션도 끝난다(design 상태 표 19행).
 */
public class RedisSubjectSessions implements SubjectSessions {

    public static final String SUBJECT_INDEX = "stove:bff:subject-tokens:";

    private final ReactiveStringRedisTemplate redis;
    private final Duration timeout;

    public RedisSubjectSessions(ReactiveStringRedisTemplate redis, Duration timeout) {
        this.redis = redis;
        this.timeout = timeout;
    }

    /** 소비자 스레드(블로킹)에서 부른다. 실패는 예외로 올려 소비자가 성공할 때까지 다시 시도한다(결정 226). */
    @Override
    public void endAll(String subject) {
        String index = SUBJECT_INDEX + subject;
        redis.opsForSet().members(index).collectList()
                .flatMap(keys -> keys.isEmpty() ? Mono.<Long>empty() : redis.delete(keys.toArray(String[]::new)))
                .then(redis.delete(index)).block(timeout);
    }
}
