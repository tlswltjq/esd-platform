package com.stove.common.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.archfixture.precision.adapter.FactoryClient;
import com.stove.archfixture.precision.adapter.oauth.RotatingTokens;
import com.stove.archfixture.precision.api.controller.FactoryController;
import com.stove.archfixture.precision.api.controller.TransactionController;
import com.stove.archfixture.precision.api.listener.ClassAsyncListener;
import com.stove.archfixture.precision.api.listener.SynchronousListener;
import com.stove.archfixture.precision.api.listener.TransactionListener;
import com.stove.archfixture.precision.api.scheduler.TransactionScheduler;
import com.stove.archfixture.precision.config.ScheduleConfiguration;
import com.stove.archfixture.precision.core.service.ApplyRows;
import com.stove.archfixture.precision.core.service.BuildValueService;
import com.stove.archfixture.precision.core.service.ReadForeignOrder;
import com.stove.archfixture.precision.core.service.ReadSharedRoot;
import com.stove.archfixture.precision.core.service.ReceiveInputs;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureResponsibilityTest {
    private JavaClasses fixtures(Class<?>... types) {
        JavaClasses classes = new ClassFileImporter().importClasses(types);
        assertThat(classes).hasSize(types.length);
        return classes;
    }

    private void rejects(ArchRule rule, Class<?> type) {
        assertThatThrownBy(() -> rule.check(fixtures(type)))
                .isInstanceOf(AssertionError.class).hasMessageContaining(type.getName());
    }

    @Test void rejectsClassLevelAsync() {
        rejects(EventOrderingRules.리스너_클래스에_Async_를_붙이지_않는다, ClassAsyncListener.class);
    }

    @Test void acceptsSynchronousClass() {
        EventOrderingRules.리스너_클래스에_Async_를_붙이지_않는다.check(fixtures(SynchronousListener.class));
    }

    @Test void beanFactoryBelongsToConfig() {
        ModulePackageRules.Bean_팩토리는_config_에_둔다.check(fixtures(ScheduleConfiguration.class));
    }

    @Test void rejectsBeanFactoryInCore() {
        rejects(ModulePackageRules.Bean_팩토리는_config_에_둔다, BuildValueService.class);
    }

    @Test void rejectsBeanFactoryInController() {
        rejects(ModulePackageRules.Bean_팩토리는_config_에_둔다, FactoryController.class);
    }

    @Test void rejectsBeanFactoryInAdapter() {
        rejects(ModulePackageRules.Bean_팩토리는_config_에_둔다, FactoryClient.class);
    }

    @Test void rejectsProgrammaticTransactionInController() {
        rejects(ModuleHygieneRules.인바운드는_프로그램_방식_트랜잭션을_열지_않는다, TransactionController.class);
    }

    @Test void rejectsTransactionManagerInListener() {
        rejects(ModuleHygieneRules.인바운드는_프로그램_방식_트랜잭션을_열지_않는다, TransactionListener.class);
    }

    @Test void rejectsTransactionOperationsInScheduler() {
        rejects(ModuleHygieneRules.인바운드는_프로그램_방식_트랜잭션을_열지_않는다, TransactionScheduler.class);
    }

    @Test void allowsServiceAndProtocolAdapterTransactions() {
        ModuleHygieneRules.인바운드는_프로그램_방식_트랜잭션을_열지_않는다
                .check(fixtures(ApplyRows.class, RotatingTokens.class));
    }

    @Test void rejectsForeignAppDomainEvenWhenThatAppIsNotImported() {
        rejects(ModulePackageRules.앱은_다른_앱의_구현을_모른다, ReadForeignOrder.class);
    }

    @Test void allowsSharedContractsAndSameAppInputs() {
        ModulePackageRules.앱은_다른_앱의_구현을_모른다.check(fixtures(ReceiveInputs.class));
    }

    @Test void allowsSharedContractsAtTheCommonRoot() {
        ModulePackageRules.앱은_다른_앱의_구현을_모른다.check(fixtures(ReadSharedRoot.class));
    }

    @Test void rejectsEmptyAppImport() {
        JavaClasses empty = new ClassFileImporter().importPackages("com.stove.no.such.application");
        assertThat(empty).isEmpty();
        assertThatThrownBy(() -> AppArchitectureRules.앱_검사_대상은_비어있지_않다(empty))
                .isInstanceOf(AssertionError.class).hasMessageContaining("@AnalyzeClasses");
    }

    @Test void acceptsNonemptyAppImport() {
        assertThatCode(() -> AppArchitectureRules.앱_검사_대상은_비어있지_않다(fixtures(ApplyRows.class)))
                .doesNotThrowAnyException();
    }
}
