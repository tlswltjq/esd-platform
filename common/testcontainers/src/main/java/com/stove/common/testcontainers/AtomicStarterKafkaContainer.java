package com.stove.common.testcontainers;

import java.io.IOException;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Publishes the Kafka starter only after its archive copy has finished (D-054). */
class AtomicStarterKafkaContainer extends ConfluentKafkaContainer {
    static final String STARTER_SCRIPT = "/tmp/testcontainers_start.sh";
    static final String STAGED_STARTER_SCRIPT = STARTER_SCRIPT + ".copying";

    AtomicStarterKafkaContainer(DockerImageName image) {
        super(image);
    }

    @Override
    public void copyFileToContainer(Transferable file, String containerPath) {
        if (!STARTER_SCRIPT.equals(containerPath)) {
            copyArchive(file, containerPath);
            return;
        }
        copyStarterScript(file);
        publishStarterScript();
    }

    protected void copyStarterScript(Transferable file) {
        copyArchive(file, STAGED_STARTER_SCRIPT);
    }

    /** Every archive copy passes through here, so a test can check that none targets the starter path. */
    protected void copyArchive(Transferable file, String containerPath) {
        super.copyFileToContainer(file, containerPath);
    }

    private void publishStarterScript() {
        try {
            // Both paths are in the same directory: rename exposes only the fully copied file.
            // Docker archive extraction owns the file as root; /tmp has the sticky bit.
            ExecConfig publish = ExecConfig.builder().user("root")
                    .command(new String[] {"mv", "-f", "--", STAGED_STARTER_SCRIPT, STARTER_SCRIPT}).build();
            ExecResult result = execInContainer(publish);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Cannot publish the Kafka starter: " + result.getStderr());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka starter publication interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot publish the Kafka starter", failure);
        }
    }
}
