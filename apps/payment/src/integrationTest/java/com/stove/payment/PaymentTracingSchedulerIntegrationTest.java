package com.stove.payment;

import static com.stove.payment.PaymentFixtures.event;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.payment.api.listener.OrderEventListener;
import com.stove.payment.api.scheduler.PendingPaymentScheduler;
import com.stove.payment.core.domain.PaymentOrderRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** Exercises the actual scheduler; no direct call to the processing service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.outbox.relay-enabled=false", "stove.payment.processing-enabled=true", "stove.payment.poll-interval-ms=100",
        "spring.kafka.listener.auto-startup=false", "management.tracing.sampling.probability=1",
        "management.otlp.tracing.export.enabled=false"})
@AutoConfigureObservability
@Import(PaymentTracingIntegrationTest.TracingConfiguration.class)
class PaymentTracingSchedulerIntegrationTest extends PaymentIntegrationTestBase {
    @Autowired OrderEventListener listener;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxEventRepository events;
    @Autowired PendingPaymentScheduler scheduler;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduling;

    @AfterEach void stopScheduler() {
        // Shared infrastructure must survive for other cached application contexts.
        scheduling.postProcessBeforeDestruction(scheduler, "pendingPaymentScheduler");
    }

    @Test void scheduledWorkerRestoresTheDurablyReceivedParent() throws Exception {
        PaymentOrderRequest input = request();
        String trace = "44444444444444444444444444444444";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.ORDER, 0, 0, input.orderNo(), mapper.writeValueAsString(event(input)));
        record.headers().add(EventHeaders.EVENT_ID, input.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, "OrderPlaced".getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.TRACE_PARENT, ("00-" + trace + "-4444444444444444-01").getBytes(StandardCharsets.UTF_8));
        listener.receive(record);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(events.findAll()).anySatisfy(event -> {
                    assertThat(event.getPartitionKey()).isEqualTo(input.orderNo());
                    assertThat(event.getTraceParent()).startsWith("00-" + trace + "-");
                }));
    }
}
