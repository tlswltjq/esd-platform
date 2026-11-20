package com.stove.archfixture.messaging.compliant;

import com.stove.common.event.InboundMessage;

/** 준수: 업무 코드는 수신 포트만 안다. */
public class ListenerUsingPort {
    public String key(InboundMessage message) {
        return message.key();
    }
}
