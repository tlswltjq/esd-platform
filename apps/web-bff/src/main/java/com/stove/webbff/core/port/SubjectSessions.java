package com.stove.webbff.core.port;

/** subject의 BFF 세션 토큰 기록 저장소(결정 225: 세션 삭제는 각 BFF가 맡는다). */
public interface SubjectSessions {

    /**
     * 그 subject의 모든 토큰 기록을 지운다. 이미 없으면 아무것도 하지 않는다(멱등). 저장소에 닿지 못하면 예외를 던진다 —
     * 소비자가 성공할 때까지 다시 시도한다(결정 226).
     */
    void endAll(String subject);
}
