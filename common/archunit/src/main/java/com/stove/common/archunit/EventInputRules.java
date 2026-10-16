package com.stove.common.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/** Inbound event isolation, applied to modules whose input conversion is complete. */
public final class EventInputRules {
    private EventInputRules() {}

    @ArchTest
    public static final ArchRule core_는_이벤트_봉투를_모른다 = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.stove.common.kafka.EventEnvelope")
            .because("봉투 검증과 외부 이벤트 변환은 api.listener 가 담당한다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 도메인_모델은_외부_이벤트를_모른다 = noClasses()
            .that().resideInAPackage("..core.domain..")
            .should().dependOnClassesThat()
            .areAssignableTo("com.stove.common.kafka.contract.DomainEvent")
            .because("도메인 모델은 내부 입력을 받고 공유 스냅샷 값만 재사용한다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule 업무_서비스는_외부_이벤트를_입력받지_않는다 = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("..core.service..")
            .should(new ArchCondition<JavaMethod>("외부 이벤트 대신 내부 입력을 받는다") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (JavaClass parameter : method.getRawParameterTypes()) {
                        if (parameter.isAssignableTo("com.stove.common.kafka.contract.DomainEvent"))
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " receives external event " + parameter.getName()));
                    }
                }
            })
            .allowEmptyShould(true);
}
