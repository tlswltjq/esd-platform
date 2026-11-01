package com.stove.common.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.archfixture.precision.adapter.RowsClient;
import com.stove.archfixture.precision.api.listener.ExecutorListener;
import com.stove.archfixture.precision.api.listener.ExecutorSubtypeListener;
import com.stove.archfixture.precision.api.listener.MethodAsyncListener;
import com.stove.archfixture.precision.api.listener.SynchronousListener;
import com.stove.archfixture.precision.api.scheduler.WorkScheduler;
import com.stove.archfixture.precision.config.ScheduleConfiguration;
import com.stove.archfixture.precision.core.port.pg.RemoteRows;
import com.stove.archfixture.precision.core.service.CallInboundService;
import com.stove.archfixture.precision.core.service.CollectRows;
import com.stove.archfixture.precision.core.service.ReadRemoteRows;
import com.stove.archfixture.precision.core.service.ReadRows;
import com.stove.archfixture.precision.core.service.ReceiveInputs;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArchitecturePrecisionTest {
    private JavaClasses fixtures(Class<?>... types) {
        JavaClasses classes = new ClassFileImporter().importClasses(types);
        assertThat(classes).hasSize(types.length);
        return classes;
    }

    private void rejects(ArchRule rule, Class<?> type) {
        assertThatThrownBy(() -> rule.check(fixtures(type)))
                .isInstanceOf(AssertionError.class).hasMessageContaining(type.getName());
    }

    @Test void allowsActionNamesBeyondAClosedVerbList() {
        ModuleHygieneRules.core_서비스_네이밍.check(fixtures(CollectRows.class));
    }

    @Test void allowsPrivateImplementationTypes() {
        ModuleHygieneRules.core_서비스_네이밍.check(fixtures(ReadRows.class, ReadRows.helperType()));
    }

    @Test void configCanAssembleInboundObjects() {
        ModulePackageRules.계층_접근_방향.check(fixtures(
                ScheduleConfiguration.class, WorkScheduler.class, ReadRows.class));
    }

    @Test void coreStillCannotReferenceInboundObjects() {
        JavaClasses classes = fixtures(CallInboundService.class, WorkScheduler.class, ReadRows.class);
        assertThatThrownBy(() -> ModulePackageRules.계층_접근_방향.check(classes))
                .isInstanceOf(AssertionError.class).hasMessageContaining(CallInboundService.class.getName());
        assertThatThrownBy(() -> ModulePackageRules.core_는_api_와_adapter_를_모른다.check(classes))
                .isInstanceOf(AssertionError.class);
    }

    @Test void rejectsPortImplementationInCoreForSubpackages() {
        assertThatThrownBy(() -> ModulePackageRules.포트_구현체는_adapter_에_둔다.check(
                fixtures(RemoteRows.class, ReadRemoteRows.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining(ReadRemoteRows.class.getName());
    }

    @Test void allowsPortImplementationInAdapterForSubpackages() {
        ModulePackageRules.포트_구현체는_adapter_에_둔다.check(fixtures(RemoteRows.class, RowsClient.class));
    }

    @Test void rejectsExecutorSubtypesInListener() {
        rejects(EventOrderingRules.리스너는_다른_스레드로_넘기지_않는다, ExecutorSubtypeListener.class);
    }

    @Test void stillRejectsDirectExecutorAndMethodAsync() {
        rejects(EventOrderingRules.리스너는_다른_스레드로_넘기지_않는다, ExecutorListener.class);
        rejects(EventOrderingRules.리스너_메서드에_Async_를_붙이지_않는다, MethodAsyncListener.class);
    }

    @Test void allowsSynchronousListener() {
        EventOrderingRules.리스너는_다른_스레드로_넘기지_않는다.check(fixtures(SynchronousListener.class));
        EventOrderingRules.리스너_메서드에_Async_를_붙이지_않는다.check(fixtures(SynchronousListener.class));
    }

    // Check each signature in the failure report so one caught parameter cannot hide another missed shape.
    @ParameterizedTest
    @ValueSource(strings = {"direct", "list", "array", "nested", "wildcard", "lowerBound", "bounded", "genericArray"})
    void rejectsEachExternalEventInputShape(String method) {
        List<String> violations = inputViolations();
        assertThat(violations).anyMatch(detail -> detail.contains("." + method + "("));
    }

    private List<String> inputViolations() {
        EvaluationResult result = EventInputRules.업무_서비스는_외부_이벤트를_입력받지_않는다
                .evaluate(fixtures(ReceiveInputs.class));
        return result.getFailureReport().getDetails();
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal", "snapshot", "publish", "recursiveBound"})
    void allowsInternalInputsSnapshotsAndOutboundEvents(String method) {
        assertThat(inputViolations()).noneMatch(detail -> detail.contains("." + method + "("));
    }
}
