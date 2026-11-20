package com.stove.archfixture.messaging.compliant;

import io.lettuce.core.RedisClient;

/** 준수: Redis 를 메시징이 아닌 용도(세션·캐시)로 쓰는 것은 막지 않는다(Gateway BFF 토큰 저장). */
public class SessionStoreUsingRedis {
    public String name(RedisClient client) {
        return client.toString();
    }
}
