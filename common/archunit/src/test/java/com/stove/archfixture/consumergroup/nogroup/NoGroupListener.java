package com.stove.archfixture.consumergroup.nogroup;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;

/**
 * 위반 — {@code group} 을 비워 두었다.
 *
 * <p>{@code application.yml} 의 {@code group-id} 기본값을 지웠으므로 이 리스너는
 * 그룹 이름을 어디서도 얻지 못한다. 예전이라면 yml 이 조용히 메워줬다.
 */
@EventSubscriber(topics = "fixture.nogroup.v1", group = "")
public class NoGroupListener implements EventHandler {

    @Override
    public void receive(InboundMessage message) {
    }
}
