package com.stove.common.messaging.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.env.MockEnvironment;

class RabbitMessagingWiringTest {

    @Test
    void outsideRabbitModeTheRabbitHealthCheckIsOffSoAnUnusedBrokerDoesNotMarkTheAppDown() {
        MockEnvironment environment = new MockEnvironment().withProperty("stove.messaging.broker", "kafka");

        new MessagingBrokerEnvironment().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.rabbitmq.publisher-confirm-type")).isNull();
    }

    @Test
    void inRabbitModePublisherConfirmsAreOnButExplicitSettingsStillWin() {
        MockEnvironment environment = new MockEnvironment().withProperty("stove.messaging.broker", "rabbitmq")
                .withProperty("management.health.rabbit.enabled", "false");

        new MessagingBrokerEnvironment().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.rabbitmq.publisher-confirm-type")).isEqualTo("correlated");
        assertThat(environment.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
    }

    @Test
    void kafkaCopyTopicsWithoutAKafkaTemplateRefuseToStart() {
        MessagePublisher primary = message -> CompletableFuture.completedFuture(null);

        assertThatThrownBy(() -> RabbitMessagingAutoConfiguration.alsoKafka(primary, Set.of("stove.catalog.v1"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stove.catalog.v1");
        assertThat(RabbitMessagingAutoConfiguration.alsoKafka(primary, Set.of(), null)).isSameAs(primary);
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherTopicsGoOnlyToRabbit() {
        MessagePublisher primary = message -> CompletableFuture.completedFuture(null);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

        MessagePublisher publisher = RabbitMessagingAutoConfiguration.alsoKafka(primary, Set.of("stove.catalog.v1"), kafka);
        publisher.publish(new OutboundMessage("stove.order.v1", "K", "{}", Map.of())).join();

        verifyNoInteractions(kafka);
    }

    @Test
    void thePublisherRefusesAConnectionWithoutCorrelatedConfirmsAndReturns() {
        // 연결은 쓰기 전까지 열리지 않으므로 브로커 없이 설정만 본다(PR #211 R2).
        CachingConnectionFactory withoutConfirms = new CachingConnectionFactory("localhost");
        CachingConnectionFactory withoutReturns = new CachingConnectionFactory("localhost");
        withoutReturns.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        CachingConnectionFactory complete = new CachingConnectionFactory("localhost");
        complete.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        complete.setPublisherReturns(true);

        assertThatThrownBy(() -> new RabbitMessagePublisher(withoutConfirms, mock(AmqpAdmin.class), 3, Set.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("publisher-confirm-type=correlated");
        assertThatThrownBy(() -> new RabbitMessagePublisher(withoutReturns, mock(AmqpAdmin.class), 3, Set.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new RabbitMessagePublisher(complete, mock(AmqpAdmin.class), 3, Set.of())).isNotNull();
    }

    @Test
    void inRabbitModeReturnsAreOnToo() {
        MockEnvironment environment = new MockEnvironment().withProperty("stove.messaging.broker", "rabbitmq");

        new MessagingBrokerEnvironment().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.rabbitmq.publisher-returns")).isEqualTo("true");
    }

    @Test
    void theSameKeyAlwaysMapsToTheSameQueue() {
        int shard = RabbitTopology.shard("ORD-1", 3);
        for (int i = 0; i < 10; i++) {
            assertThat(RabbitTopology.shard("ORD-1", 3)).isEqualTo(shard);
        }
        assertThat(RabbitTopology.shard(null, 3)).isBetween(0, 2);
        assertThat(RabbitTopology.queue("order-payment-result", "stove.payment.v1", 2))
                .isEqualTo("order-payment-result.stove.payment.v1.2");
    }
}
