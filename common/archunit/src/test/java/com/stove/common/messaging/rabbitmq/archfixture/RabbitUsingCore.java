package com.stove.common.messaging.rabbitmq.archfixture;

import com.stove.common.messaging.archfixture.CoreUsingPorts;

/** 준수: adapter 는 코어와 자기 패키지만 쓴다. */
public class RabbitUsingCore {
    public String name(CoreUsingPorts core, RabbitUsingCore self) {
        return core.toString() + self;
    }
}
