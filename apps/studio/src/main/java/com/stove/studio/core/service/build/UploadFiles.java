package com.stove.studio.core.service.build;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disk staging keeps whole build uploads out of heap memory. */
public final class UploadFiles {
    private static final Logger LOG =
            LoggerFactory.getLogger(UploadFiles.class);

    public static void cleanup(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            LOG.warn("임시 업로드 파일 정리 실패: {}", file, failure);
        }
    }

    private UploadFiles() {}

    public static Path stage(InputStream input, long limit) throws IOException {
        Path file = Files.createTempFile("studio-upload-", ".tmp");
        try (OutputStream output = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > limit)
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "파일 최대 크기를 초과했습니다.");
                output.write(buffer, 0, read);
            }
            if (size == 0)
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "빈 파일은 등록할 수 없습니다.");
            return file;
        } catch (IOException | RuntimeException failure) {
            cleanup(file);
            throw failure;
        }
    }

    public static String checksum(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void requireZip(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() == 0 || zip.size() > 100000) throw new IllegalArgumentException();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                Path path = Path.of(name).normalize();
                if (path.isAbsolute() || path.startsWith("..") || name.matches("^[A-Za-z]:.*"))
                    throw new IllegalArgumentException();
            }
        } catch (IOException | IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "올바른 ZIP 빌드 파일이 필요합니다.");
        }
    }
}
