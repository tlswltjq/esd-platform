package com.stove.common.messaging.archfixture;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import java.util.Map;

/** 준수: 코어는 포트만 쓴다. */
public class CoreUsingPorts {
    public void send(MessagePublisher publisher) {
        publisher.publish(new OutboundMessage("t", "k", "{}", Map.of())).join();
    }
}
