package com.stove.bench.messaging.app.api.listener;

import com.stove.bench.messaging.app.api.application.ReceiveBenchMessage;
import com.stove.bench.messaging.app.core.service.RecordBenchMessage;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 시험 리스너. 운영 리스너와 같은 모양(Boot 기본 팩토리, {@code ConsumerRecord<String, String>} 하나)이라 같은 오류 처리기·
 * 멈춘 키 인터셉터·동시성 기본값(결정 212)을 받는다.
 */
@Component
@RequiredArgsConstructor
public class BenchMessageListener {

    private final ReceiveBenchMessage receive;

    @KafkaListener(topics = "${stove.bench.topic}", groupId = RecordBenchMessage.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        receive.receive(BenchMessageMapper.from(record), record.partition(), record.offset());
    }
}
