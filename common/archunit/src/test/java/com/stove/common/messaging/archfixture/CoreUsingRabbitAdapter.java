package com.stove.common.messaging.archfixture;

import com.stove.common.messaging.rabbitmq.archfixture.RabbitUsingCore;

/** 위반: 메시징 코어가 adapter 패키지를 안다(결정 209). */
public class CoreUsingRabbitAdapter {
    public String name(RabbitUsingCore adapter) {
        return adapter.toString();
    }
}
