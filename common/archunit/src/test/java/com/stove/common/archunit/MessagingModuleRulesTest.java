package com.stove.common.archunit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.archfixture.ContractUsingCore;
import com.stove.common.contract.archfixture.ContractUsingKafkaRecord;
import com.stove.common.contract.archfixture.ContractUsingKafkaTemplate;
import com.stove.common.messaging.archfixture.CoreFixture;
import com.stove.common.messaging.kafka.archfixture.KafkaFixture;
import com.stove.common.messaging.kafka.archfixture.KafkaUsingCore;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * 메시징 모듈 방향 규칙(결정 227)이 실제로 위반을 잡고, 지킨 코드는 통과시키는가.
 * 규칙은 {@code allowEmptyShould(true)} 라 패키지 이름이 어긋나면 조용히 통과한다 — 그래서 규칙 자신을 검사한다.
 */
class MessagingModuleRulesTest {

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
    void theKafkaModuleMayNotKnowTheJpaCore() {
        catches(CommonLibraryRules.카프카는_JPA와_Outbox를_모른다, of(KafkaUsingCore.class, CoreFixture.class));
        accepts(CommonLibraryRules.카프카는_JPA와_Outbox를_모른다, of(KafkaFixture.class));
    }

    @Test
    void theEventContractKnowsKafkaRecordsButNotListenerInfrastructureOrMessagingModules() {
        accepts(CommonLibraryRules.이벤트_계약은_리스너_인프라와_메시징_모듈을_모른다, of(ContractUsingKafkaRecord.class));
        catches(CommonLibraryRules.이벤트_계약은_리스너_인프라와_메시징_모듈을_모른다, of(ContractUsingKafkaTemplate.class));
        catches(CommonLibraryRules.이벤트_계약은_리스너_인프라와_메시징_모듈을_모른다, of(ContractUsingCore.class, CoreFixture.class));
    }
}
