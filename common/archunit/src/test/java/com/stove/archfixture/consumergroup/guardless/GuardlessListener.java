package com.stove.archfixture.consumergroup.guardless;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;

/** 준수 — 그룹은 명시하되 멱등 키는 없다. 대조 대상이 아니다. */
@EventSubscriber(topics = "fixture.guardless.v1", group = "guardless")
public class GuardlessListener implements EventHandler {

    @Override
    public void receive(InboundMessage message) {
    }
}
