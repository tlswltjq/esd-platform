package com.stove.studio.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventEnvelope;
import com.stove.common.event.payload.UserRegisteredEvent;
import com.stove.studio.core.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthEventListener {
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = Topics.AUTH, groupId = WorkspaceService.CONSUMER_GROUP)
    public void onAuthEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(EventType.USER_REGISTERED)) return;
        UserRegisteredEvent event = envelope.payloadAs(objectMapper, UserRegisteredEvent.class);
        workspaceService.receiveRegistration(envelope.eventId(), envelope.eventType(), event.subject());
    }
}
