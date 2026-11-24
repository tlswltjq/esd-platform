package com.stove.webbff.core.service;

import com.stove.webbff.core.domain.SubjectAccessChange;
import com.stove.webbff.core.port.ProcessingLag;
import com.stove.webbff.core.port.SubjectSessions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 계정 접근 변경 사실로 그 subject의 BFF 세션 토큰 기록을 모두 지운다(결정 180·225, R5.6). 다음 요청은 401이고 세션도 끝난다.
 * 지우기는 멱등이라 같은 사실을 다시 받아도 결과가 같다. 이 BFF가 지우기 전에 끼어든 갱신이 있어도 그 토큰은 변경 전에
 * 발급돼 API 게이트웨이가 {@code iat}로 거절한다.
 */
@Service
@RequiredArgsConstructor
public class EndSubjectSessions {

    /** 소비자 그룹. API 게이트웨이(거절 기준)와 다른 그룹으로 같은 사실을 받는다(결정 225). */
    public static final String CONSUMER_GROUP = "web-bff-account-access";

    private final SubjectSessions sessions;
    private final ProcessingLag lag;

    public void execute(SubjectAccessChange change) {
        lag.started(change.changedAt());
        sessions.endAll(change.subject());
        lag.finished();
    }
}
