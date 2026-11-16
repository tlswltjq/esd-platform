package com.stove.archfixture.consumergroup.missing;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;

/** 그룹은 명시돼 있다. 없어진 것은 대조 상대인 멱등 키 쪽이다. */
@EventSubscriber(topics = "fixture.missing.v1", group = "missing")
public class MissingConstantListener implements EventHandler {

    @Override
    public void receive(InboundMessage message) {
    }
}
