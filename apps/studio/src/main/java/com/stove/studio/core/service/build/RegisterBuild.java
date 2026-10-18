package com.stove.studio.core.service.build;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.domain.build.BuildRepository;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.project.ReadProjects;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@RequiredArgsConstructor
public class RegisterBuild {
    private final ReadProjects readProjects;
    private final ProjectRepository projects;
    private final BuildRepository builds;
    private final BuildStorage storage;
    private final PlatformTransactionManager transactions;
    private final long maxArtifactBytes;

    public Build execute(
            Long id, String subject, String version, String fileName, InputStream input)
            throws IOException {
        Project initial = readProjects.requireOwned(id, subject);
        long expected = initial.getContentVersion();
        Path file = UploadFiles.stage(input, maxArtifactBytes);
        String path = null;
        try {
            UploadFiles.requireZip(file);
            String checksum = UploadFiles.checksum(file);
            long size = Files.size(file);
            path = storage.put(id, file, "zip", "application/zip");
            String stored = path;
            return new TransactionTemplate(transactions)
                    .execute(
                            tx -> {
                                Project p =
                                        projects.lock(id)
                                                .orElseThrow(
                                                        () ->
                                                                new BusinessException(
                                                                        ErrorCode.NOT_FOUND));
                                p.requireOwner(subject);
                                p.requireVersion(expected);
                                if (builds.findByProjectIdAndVersion(id, version).isPresent())
                                    throw new BusinessException(
                                            ErrorCode.CONFLICT, "이미 등록된 빌드 버전입니다.");
                                Build b =
                                        builds.saveAndFlush(
                                                Build.create(
                                                        id,
                                                        version,
                                                        safeName(fileName),
                                                        stored,
                                                        size,
                                                        checksum));
                                p.selectBuild(b.getId(), expected);
                                return b;
                            });
        } catch (RuntimeException | IOException failure) {
            if (path != null) {
                try {
                    storage.delete(path);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        } finally {
            UploadFiles.cleanup(file);
        }
    }

    private String safeName(String fileName) {
        if (fileName == null) return "build.zip";
        String result = fileName.replace('\\', '/');
        result = result.substring(result.lastIndexOf('/') + 1);
        return result.substring(0, Math.min(255, result.length()));
    }
}
