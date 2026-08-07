package com.stove.archfixture.consumergroup.nogroup;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * 위반 — {@code groupId} 를 빠뜨렸다.
 *
 * <p>{@code application.yml} 의 {@code group-id} 기본값을 지웠으므로 이 리스너는
 * 그룹 이름을 어디서도 얻지 못한다. 예전이라면 yml 이 조용히 메워줬다.
 */
public class NoGroupListener {

    @KafkaListener(topics = "fixture.nogroup.v1")
    public void onEvent(String payload) {
    }
}
