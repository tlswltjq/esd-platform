package com.stove.common.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.archfixture.messaging.compliant.ListenerUsingPort;
import com.stove.archfixture.messaging.compliant.SessionStoreUsingRedis;
import com.stove.archfixture.messaging.violating.ListenerUsingKafkaAdapter;
import com.stove.archfixture.messaging.violating.ListenerUsingKafkaRecord;
import com.stove.common.contract.archfixture.ContractUsingKafka;
import com.stove.common.messaging.archfixture.CoreUsingKafkaTemplate;
import com.stove.common.messaging.archfixture.CoreUsingPorts;
import com.stove.common.messaging.archfixture.CoreUsingRabbitAdapter;
import com.stove.common.messaging.kafka.archfixture.KafkaFixture;
import com.stove.common.messaging.rabbitmq.archfixture.RabbitUsingCore;
import com.stove.common.messaging.rabbitmq.archfixture.RabbitUsingKafkaAdapter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * 결정 209 메시징 경계 규칙이 실제로 위반을 잡고, 지킨 코드는 통과시키는가.
 * 규칙은 {@code allowEmptyShould(true)} 라 패키지 이름이 어긋나면 조용히 통과한다 — 그래서 규칙 자신을 검사한다.
 */
class MessagingBoundaryRulesTest {

    private static JavaClasses of(Class<?>... classes) {
        return new ClassFileImporter().importClasses(classes);
    }

    private static void catches(ArchRule rule, JavaClasses classes) {
        assertThatThrownBy(() -> rule.check(classes)).isInstanceOf(AssertionError.class);
    }

    private static void accepts(ArchRule rule, JavaClasses classes) {
        assertThatCode(() -> rule.check(classes)).doesNotThrowAnyException();
    }

    @Test
    void theCoreMayNotKnowABrokerClientOrAnAdapter() {
        catches(CommonLibraryRules.메시징_코어는_브로커를_모른다, of(CoreUsingKafkaTemplate.class));
        catches(CommonLibraryRules.메시징_코어는_브로커를_모른다, of(CoreUsingRabbitAdapter.class, RabbitUsingCore.class));
        accepts(CommonLibraryRules.메시징_코어는_브로커를_모른다, of(CoreUsingPorts.class, RabbitUsingCore.class));
    }

    @Test
    void anAdapterMayUseTheCoreButNotAnotherAdapter() {
        catches(CommonLibraryRules.메시징_adapter는_서로를_모른다, of(RabbitUsingKafkaAdapter.class, KafkaFixture.class));
        accepts(CommonLibraryRules.메시징_adapter는_서로를_모른다, of(RabbitUsingCore.class, CoreUsingPorts.class));
    }

    @Test
    void theKafkaAdapterMayNotKnowTheCore() {
        accepts(CommonLibraryRules.카프카는_JPA와_Outbox를_모른다, of(KafkaFixture.class));
    }

    @Test
    void theEventContractMayNotKnowABroker() {
        catches(CommonLibraryRules.이벤트_계약은_브로커를_모른다, of(ContractUsingKafka.class));
    }

    @Test
    void businessCodeMayNotKnowABrokerClientOrAnAdapter() {
        catches(MessagingBoundaryRules.앱은_브로커를_모른다, of(ListenerUsingKafkaRecord.class));
        catches(MessagingBoundaryRules.앱은_브로커를_모른다, of(ListenerUsingKafkaAdapter.class, KafkaFixture.class));
        accepts(MessagingBoundaryRules.앱은_브로커를_모른다, of(ListenerUsingPort.class));
        accepts(MessagingBoundaryRules.앱은_브로커를_모른다, of(SessionStoreUsingRedis.class));
    }

    @Test
    void adapterNamesAreMatchedByPackageRootNotPrefix() {
        assertThat(DependOnAnotherAdapter.adapterOf("com.stove.common.messaging.kafka.ops"))
                .isEqualTo("kafka");
        assertThat(DependOnAnotherAdapter.adapterOf("com.stove.common.messaging.kafkaish"))
                .isNull();
        assertThat(DependOnAnotherAdapter.adapterOf("com.stove.common.messaging.outbox"))
                .isNull();
    }
}
