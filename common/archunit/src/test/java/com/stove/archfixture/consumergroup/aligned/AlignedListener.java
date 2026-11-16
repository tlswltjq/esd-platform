package com.stove.archfixture.consumergroup.aligned;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;

/** 준수 — 리스너가 서비스 상수를 참조한다. */
@EventSubscriber(topics = "fixture.aligned.v1", group = AlignedService.CONSUMER_GROUP)
public class AlignedListener implements EventHandler {

    @Override
    public void receive(InboundMessage message) {
    }
}
