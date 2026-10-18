package com.stove.studio.core.service.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class UploadFilesTest {
    @Test
    void acceptsWholeZipWithoutPlatformExecutableOrManifest() throws Exception {
        byte[] bytes = zip("game.bin");
        Path file = UploadFiles.stage(new ByteArrayInputStream(bytes), 1024 * 1024);
        try {
            UploadFiles.requireZip(file);
            assertThat(UploadFiles.checksum(file)).hasSize(64);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void rejectsUnsafeZipPathsAndInvalidArchive() throws Exception {
        for (byte[] bytes : new byte[][] {zip("../outside"), "not a zip".getBytes()}) {
            Path file = UploadFiles.stage(new ByteArrayInputStream(bytes), 1024 * 1024);
            try {
                assertThatThrownBy(() -> UploadFiles.requireZip(file))
                        .isInstanceOf(BusinessException.class);
            } finally {
                Files.delete(file);
            }
        }
    }

    @Test
    void rejectsEmptyAndOversizedStream() {
        assertThatThrownBy(() -> UploadFiles.stage(new ByteArrayInputStream(new byte[0]), 10))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> UploadFiles.stage(new ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOf(BusinessException.class);
    }

    private byte[] zip(String entry) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write("game".getBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
