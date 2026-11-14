package com.stove.common.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

@Timeout(240)
class KafkaStarterPublicationIntegrationTest {
    private static final String RELEASE = "/tmp/copy-release";
    private static final String OPEN = "/tmp/copy-open";
    private static final String CLOSED = "/tmp/copy-closed";

    @Test
    @DisplayName("[D-054] 전제: 쓰기 FD가 열린 실행 파일은 126·Text file busy로 거부된다")
    void executingAFileOpenForWritingFailsWithTextFileBusy() throws Exception {
        // 전제 확인용이다. 수정 코드(AtomicStarterKafkaContainer)와 Testcontainers의 복사 경합은 거치지 않는다.
        // 기대: 다른 프로세스가 쓰기 FD를 쥔 파일을 실행하면 커널이 ETXTBSY로 거부한다(CI 실패와 같은 종료 코드 126).
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
    @DisplayName("[D-054] 시작 스크립트는 복사를 마친 파일 그대로(같은 inode) 이름만 바꿔 게시된다")
    void kafkaStartsOnlyAfterTheStarterCopyHasClosedItsWriter() throws Exception {
        // 기대: 복사 중에는 실행 경로가 없고, 게시는 복사본의 이름 변경이라 실행 경로의 inode가 복사본과 같다.
        // 실제(수정 전): 실행 경로에 바로 압축을 풀어, 쓰기 FD가 닫히기 전에 실행되면 126 Text file busy로 끝난다.
        // 영향: 공유 Kafka를 쓰는 통합 테스트의 컨텍스트 시작이 가끔 실패한다(CI 불안정).
        try (ObservedCopyKafka kafka = new ObservedCopyKafka()) {
            kafka.waitingFor(SharedContainers.kafkaReady());
            kafka.start();
            assertThat(kafka.observedBusyStagingFile).isTrue();
            assertThat(inode(kafka, AtomicStarterKafkaContainer.STARTER_SCRIPT))
                    .as("Publication must rename the staged copy, not write a new file")
                    .isEqualTo(kafka.stagedInode);
            assertThat(kafka.execInContainer("test", "-e", AtomicStarterKafkaContainer.STAGED_STARTER_SCRIPT)
                    .getExitCode()).as("The staged copy must be gone after publication").isEqualTo(1);
            assertThat(kafka.execInContainer("kafka-broker-api-versions", "--bootstrap-server", "localhost:9093")
                    .getExitCode()).isZero();
        }
    }

    private static String inode(GenericContainer<?> container, String path) throws IOException, InterruptedException {
        ExecResult result = container.execInContainer("stat", "-c", "%i", path);
        assertThat(result.getExitCode()).as("stat %s: %s", path, result.getStderr()).isZero();
        return result.getStdout().trim();
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
        private String stagedInode;

        private ObservedCopyKafka() {
            super(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));
        }

        @Override
        protected void copyStarterScript(Transferable file) {
            super.copyStarterScript(file);
            try {
                assertThat(execInContainer("test", "-e", STARTER_SCRIPT).getExitCode())
                        .as("The consumer path must stay absent until the copy finishes").isEqualTo(1);
                stagedInode = inode(this, STAGED_STARTER_SCRIPT);
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
