package com.stove.common.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

@Tag("known-defect")
class KafkaStarterPublicationIntegrationTest {
    private static final String RELEASE = "/tmp/copy-release";
    private static final String OPEN = "/tmp/copy-open";
    private static final String CLOSED = "/tmp/copy-closed";

    @Test
    void executingAStarterStillOpenForWritingReproducesTheCiFailure() throws Exception {
        try (GenericContainer<?> fixture = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withCommand("sh", "-c", "echo FIXTURE_READY; exec sleep 600")
                .waitingFor(Wait.forLogMessage(".*FIXTURE_READY.*", 1).withStartupTimeout(Duration.ofSeconds(30)))) {
            fixture.start();
            fixture.copyFileToContainer(Transferable.of("#!/bin/sh\necho STARTED\n", 0777),
                    AtomicStarterKafkaContainer.STARTER_SCRIPT);
            holdWriter(fixture, AtomicStarterKafkaContainer.STARTER_SCRIPT);
            try {
                ExecResult result = fixture.execInContainer("sh", "-c", AtomicStarterKafkaContainer.STARTER_SCRIPT);
                assertThat(result.getExitCode()).isEqualTo(126);
                assertThat(result.getStderr()).contains("Text file busy");
            } finally {
                releaseWriter(fixture);
            }
        }
    }

    @Test
    void kafkaStartsOnlyAfterTheStarterCopyHasClosedItsWriter() throws Exception {
        try (ObservedCopyKafka kafka = new ObservedCopyKafka()) {
            kafka.waitingFor(SharedContainers.kafkaReady());
            kafka.start();
            assertThat(kafka.observedBusyStagingFile).isTrue();
            assertThat(kafka.execInContainer("kafka-broker-api-versions", "--bootstrap-server", "localhost:9093")
                    .getExitCode()).isZero();
        }
    }

    private static void holdWriter(GenericContainer<?> container, String path)
            throws IOException, InterruptedException {
        String command = "mkfifo " + RELEASE + "; "
                + "sh -c 'exec 9>> \"$1\"; touch " + OPEN + "; read -r value < " + RELEASE
                + "; exec 9>&-; touch " + CLOSED + "' sh " + path + " >/dev/null 2>&1 & "
                + "while [ ! -f " + OPEN + " ]; do sleep 0.01; done";
        assertThat(container.execInContainer("sh", "-c", command).getExitCode()).isZero();
    }

    private static void releaseWriter(GenericContainer<?> container) throws IOException, InterruptedException {
        String command = "printf done > " + RELEASE + "; while [ ! -f " + CLOSED + " ]; do sleep 0.01; done";
        assertThat(container.execInContainer("sh", "-c", command).getExitCode()).isZero();
    }

    private static final class ObservedCopyKafka extends AtomicStarterKafkaContainer {
        private boolean observedBusyStagingFile;

        private ObservedCopyKafka() {
            super(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));
        }

        @Override
        protected void copyStarterScript(Transferable file) {
            super.copyStarterScript(file);
            try {
                assertThat(execInContainer("test", "-e", STARTER_SCRIPT).getExitCode())
                        .as("The consumer path must stay absent until the copy finishes").isEqualTo(1);
                holdWriter(this, STAGED_STARTER_SCRIPT);
                try {
                    ExecResult busy = execInContainer("sh", "-c", STAGED_STARTER_SCRIPT);
                    assertThat(busy.getExitCode()).isEqualTo(126);
                    assertThat(busy.getStderr()).contains("Text file busy");
                    observedBusyStagingFile = true;
                } finally {
                    releaseWriter(this);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Starter copy observation interrupted", interrupted);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot observe the starter copy", failure);
            }
        }
    }
}
