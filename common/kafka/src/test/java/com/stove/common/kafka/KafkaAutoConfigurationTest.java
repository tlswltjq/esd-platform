package com.stove.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stove.common.kafka.ops.DltOpsAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaAutoConfigurationTest {

    private static final AutoConfigurations CONFIGURATIONS = AutoConfigurations.of(
            KafkaConsumerAutoConfiguration.class, DltOpsAutoConfiguration.class);

    @Test
    void contractsCanBeUsedWithoutKafkaBeansOrWeb() {
        new ApplicationContextRunner()
                .withConfiguration(CONFIGURATIONS)
                .withClassLoader(new FilteredClassLoader("com.stove.common.web", "jakarta.servlet"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("stoveKafkaErrorHandler");
                    assertThat(context).doesNotHaveBean("dltOpsController");
                });
    }

    @Test
    void consumerPolicyWorksWithoutWebContract() {
        new ApplicationContextRunner()
                .withConfiguration(CONFIGURATIONS)
                .withClassLoader(new FilteredClassLoader("com.stove.common.web"))
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("stoveKafkaErrorHandler");
                    assertThat(context).doesNotHaveBean("dltOpsController");
                });
    }

    @Test
    void servletOpsRequiresWebContract() {
        webRunner().withClassLoader(new FilteredClassLoader("com.stove.common.web"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("dltOpsController");
                });
    }

    @Test
    void servletOpsCanBeEnabledOrDisabled() {
        webRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("dltOpsController");
        });
        webRunner().withPropertyValues("stove.messaging.ops.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("dltOpsController");
        });
    }

    private WebApplicationContextRunner webRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(CONFIGURATIONS)
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(ConsumerFactory.class, () -> mock(ConsumerFactory.class));
    }
}
