package com.stove.e2e;

import static org.assertj.core.api.Assertions.fail;

import java.util.Map;

/**
 * 장애 주입 장({@link ServiceOutageChaosTest})이 쓰는 회원 세션 자리.
 *
 * <p>이 값을 채우던 옛 인수 여정(트랙 A·B·C)은 지금 없는 API를 불러 지웠다(플랫폼 T43). 지금은 세션을 만드는
 * 곳이 없어 장애 주입 장은 선행 단계 실패로 끝난다. 다시 세울지는 플랫폼 tasks.md의 T48에서 정한다.
 *
 * <h2>건너뛴 것을 통과로 읽지 않는다</h2>
 *
 * <p><b>{@code Assumptions} 를 쓰지 않는다.</b> 선행 단계가 값을 만들지 못했으면 뒤 장은 <i>건너뛴 것(skipped)</i>
 * 이 아니라 <b>실패</b>다. 스킵은 리포트에서 초록 옆에 조용히 앉지만, 실패는 이유를 말하며 빨개진다.
 */
final class Journey {

    static long MEMBER;

    private static BffSession member;

    private Journey() {
    }

    /** The BFF session determines ownership; a caller-supplied member header has no authority. */
    static Map<String, String> asMember(long memberId) {
        BffSession session = memberId == MEMBER ? member : null;
        return require(session, "member BFF session", "회원 로그인").headers();
    }

    static void member(long id, BffSession session) { MEMBER = id; member = session; }

    /**
     * 값이 없으면 <b>이 장을 실패시킨다.</b> 스킵이 아니다 — 위 클래스 주석 참고.
     * 메시지에 "누가 만들었어야 하는지" 를 적는다. 실패를 본 사람이 다음에 볼 곳이 그것이다.
     */
    private static <T> T require(T value, String what, String producedBy) {
        if (value == null) {
            fail("선행 단계가 %s 를 만들지 못했다 (%s). 이 장은 건너뛴 것이 아니라 검증되지 않은 것이다."
                    .formatted(what, producedBy));
        }
        return value;
    }
}
