package com.stove.common.archunit;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/** 메시징 adapter 클래스가 다른 adapter 패키지의 클래스에 의존하는가(결정 209). */
final class DependOnAnotherAdapter {

    private static final String ADAPTER_ROOT = "com.stove.common.messaging.";
    private static final String[] ADAPTER_NAMES = {"kafka", "rabbitmq", "redis", "direct"};

    static final ArchCondition<JavaClass> CONDITION = new ArchCondition<>("다른 메시징 adapter 에 의존한다") {
        @Override
        public void check(JavaClass origin, ConditionEvents events) {
            String own = adapterOf(origin.getPackageName());
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String target = adapterOf(dependency.getTargetClass().getPackageName());
                if (target != null && !target.equals(own)) {
                    events.add(SimpleConditionEvent.satisfied(dependency, dependency.getDescription()));
                }
            }
        }
    };

    private DependOnAnotherAdapter() {
    }

    /** 패키지가 속한 adapter 이름. adapter 가 아니면 {@code null}. */
    static String adapterOf(String packageName) {
        for (String name : ADAPTER_NAMES) {
            String root = ADAPTER_ROOT + name;
            if (packageName.equals(root) || packageName.startsWith(root + ".")) {
                return name;
            }
        }
        return null;
    }
}
