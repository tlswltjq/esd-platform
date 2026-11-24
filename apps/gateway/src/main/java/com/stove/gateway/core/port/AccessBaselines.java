package com.stove.gateway.core.port;

import com.stove.gateway.core.domain.AccessChange;

/** subject별 거절 기준(가장 늦은 {@code changedAt}) 저장소. */
public interface AccessBaselines {

    /**
     * 처음 보는 {@code eventId}면 그 subject의 기준을 {@code changedAt}으로 올린다(더 늦은 값만 덮는다). 이미 본 사실이면
     * 아무것도 하지 않는다. 저장소에 닿지 못하면 예외를 던진다 — 소비자가 성공할 때까지 다시 시도한다(결정 226).
     *
     * @return 이번에 처음 반영했으면 참.
     */
    boolean raise(AccessChange change);
}
