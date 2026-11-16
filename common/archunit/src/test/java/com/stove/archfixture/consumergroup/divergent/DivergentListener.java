package com.stove.archfixture.consumergroup.divergent;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;

/** 위반 — 리터럴로 되돌아가 소비자 그룹이 멱등 키와 갈라졌다. */
@EventSubscriber(topics = "fixture.divergent.v1", group = "kafka-side")
public class DivergentListener implements EventHandler {

    @Override
    public void receive(InboundMessage message) {
    }
}
