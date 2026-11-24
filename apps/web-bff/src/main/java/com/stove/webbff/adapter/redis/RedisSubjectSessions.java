package com.stove.webbff.adapter.redis;

import com.stove.webbff.core.port.SubjectSessions;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * subject 색인({@code stove:bff:subject-tokens:<subject>})으로 그 subject의 토큰 기록을 모두 지운다. 색인은 토큰 기록을 쓸 때
 * {@code RedisAuthorizedClients}가 채운다. 토큰 기록이 없으면 다음 요청은 401이고 그 세션도 끝난다(design 상태 표 19행).
 *
 * <p>색인 읽기·토큰 기록 삭제·색인 정리를 한 스크립트로 원자적으로 한다. 따로 하면 읽은 뒤 끼어든 로그인·갱신이 색인에 더한
 * 키까지 색인에서 지워, 그 토큰을 다음 사실이 찾지 못한다(PR #246 R2). 지우는 것은 읽은 멤버뿐이다. 스크립트 뒤에 쓰인
 * 토큰은 색인에 남는다 — 변경 뒤 새로 로그인한 세션이거나, 변경 전 발급이라 API 게이트웨이가 {@code iat}로 거절하는 토큰이다.
 */
public class RedisSubjectSessions implements SubjectSessions {

    public static final String SUBJECT_INDEX = "stove:bff:subject-tokens:";

    private static final RedisScript<Long> END_ALL = RedisScript.of("""
            local keys = redis.call('smembers', KEYS[1])
            for _, key in ipairs(keys) do
              redis.call('del', key)
              redis.call('srem', KEYS[1], key)
            end
            return #keys
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final Duration timeout;

    public RedisSubjectSessions(ReactiveStringRedisTemplate redis, Duration timeout) {
        this.redis = redis;
        this.timeout = timeout;
    }

    /** 소비자 스레드(블로킹)에서 부른다. 실패는 예외로 올려 소비자가 성공할 때까지 다시 시도한다(결정 226). */
    @Override
    public void endAll(String subject) {
        redis.execute(END_ALL, List.of(SUBJECT_INDEX + subject), List.of()).next().block(timeout);
    }
}
