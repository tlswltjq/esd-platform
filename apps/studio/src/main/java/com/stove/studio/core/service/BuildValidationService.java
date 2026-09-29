package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.BuildStatus;
import com.stove.studio.core.domain.BuildValidationSnapshot;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.port.MalwareScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuildValidationService {

    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_EXPANSION_RATIO = 20;

    private final GameBuildRepository buildRepository;
    private final BuildStorage buildStorage;
    private final MalwareScanner malwareScanner;
    private final BuildValidationResultService resultService;
    private final ObjectMapper objectMapper;

    public void validate(Long buildId) {
        BuildValidationSnapshot build = snapshot(buildId);
        if (build.status() != BuildStatus.PROCESSING) {
            return;
        }

        Path artifact = null;
        try {
            artifact = Files.createTempFile("esd-build-", ".zip");
            // AWS SDK's ResponseTransformer.toFile creates the target itself and
            // rejects an already-created path. Keep the name reserved but leave
            // the destination absent before downloading the object.
            Files.deleteIfExists(artifact);
            buildStorage.downloadTo(build.storagePath(), artifact);
            String actualChecksum = sha256(artifact);
            if (!normalizeChecksum(build.expectedChecksum()).equals(actualChecksum)) {
                resultService.failed(buildId, "CHECKSUM_MISMATCH");
                return;
            }
            if (!malwareScanner.clean(artifact)) {
                resultService.failed(buildId, "MALWARE_DETECTED");
                return;
            }
            String archiveFailure = validateArchive(artifact, build.expectedSize(), build.productVersion());
            if (archiveFailure != null) {
                resultService.failed(buildId, archiveFailure);
                return;
            }
            resultService.validated(buildId, actualChecksum);
        } catch (Exception failure) {
            log.warn("빌드 검증 실패 buildId={}", buildId, failure);
            resultService.failed(buildId, "VALIDATION_ERROR");
        } finally {
            if (artifact != null) {
                try {
                    Files.deleteIfExists(artifact);
                } catch (Exception cleanupFailure) {
                    log.warn("검증 임시 파일 삭제 실패 path={}", artifact, cleanupFailure);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    BuildValidationSnapshot snapshot(Long buildId) {
        GameBuild build = buildRepository.findById(buildId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "buildId=" + buildId));
        return new BuildValidationSnapshot(build.getStatus(), build.getStoragePath(),
                build.getChecksum(), build.getFileSize(), build.getVersion());
    }

    private String validateArchive(Path artifact, long compressedSize, String expectedVersion) {
        ZipEntry manifest = null;
        boolean executable = false;
        int count = 0;
        long uncompressed = 0;
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                count++;
                if (count > MAX_ENTRIES) {
                    return "TOO_MANY_ARCHIVE_ENTRIES";
                }
                Path normalized = Path.of(entry.getName()).normalize();
                if (normalized.isAbsolute() || normalized.startsWith("..")) {
                    return "UNSAFE_ARCHIVE_PATH";
                }
                if (entry.getSize() > 0) {
                    uncompressed = Math.addExact(uncompressed, entry.getSize());
                    if (uncompressed > Math.max(compressedSize * MAX_EXPANSION_RATIO, 512L * 1024L * 1024L)) {
                        return "ARCHIVE_EXPANSION_LIMIT";
                    }
                }
                String name = entry.getName().toLowerCase();
                if (name.equals("manifest.json") || name.endsWith("/manifest.json")) {
                    manifest = entry;
                }
                executable |= !entry.isDirectory() && name.endsWith(".exe");
            }
            if (manifest == null) {
                return "MANIFEST_MISSING";
            }
            if (manifest.getSize() > 1024L * 1024L) {
                return "MANIFEST_TOO_LARGE";
            }
            try (InputStream input = zip.getInputStream(manifest)) {
                var json = objectMapper.readTree(input);
                String entrypoint = json.path("entrypoint").asText();
                if (!expectedVersion.equals(json.path("productVersion").asText())
                        || entrypoint.isBlank() || zip.getEntry(entrypoint) == null) {
                    return "INVALID_MANIFEST";
                }
            }
        } catch (Exception invalidZip) {
            return "INVALID_ARCHIVE";
        }
        return executable ? null : "EXECUTABLE_MISSING";
    }

    private String sha256(Path artifact) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String normalizeChecksum(String checksum) {
        return checksum.toLowerCase().replaceFirst("^sha256:", "");
    }

}
