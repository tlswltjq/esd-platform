package com.stove.studio.core.service.project;

import com.stove.common.web.error.*;
import com.stove.studio.core.domain.project.*;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.build.UploadFiles;

import lombok.RequiredArgsConstructor;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.file.*;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

@RequiredArgsConstructor
public class UploadThumbnail {
    private final ReadProjects readProjects;
    private final ProjectRepository projects;
    private final BuildStorage storage;
    private final PlatformTransactionManager transactions;
    private final String publicBaseUrl;
    private final long maxThumbnailBytes;

    public ThumbnailAsset execute(Long id, String subject, InputStream input) throws IOException {
        Project initial = readProjects.requireOwned(id, subject);
        long version = initial.getContentVersion();
        Path file = UploadFiles.stage(input, maxThumbnailBytes);
        String path = null;
        try {
            String format;
            try (ImageInputStream image = ImageIO.createImageInputStream(file.toFile())) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(image);
                if (!readers.hasNext())
                    throw new BusinessException(
                            ErrorCode.INVALID_REQUEST, "PNG 또는 JPEG 이미지가 필요합니다.");
                ImageReader reader = readers.next();
                try {
                    format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    reader.setInput(image);
                    if (!java.util.List.of("png", "jpeg").contains(format)
                            || reader.getWidth(0) > 8192
                            || reader.getHeight(0) > 8192)
                        throw new BusinessException(
                                ErrorCode.INVALID_REQUEST, "PNG/JPEG 이미지의 크기는 8192픽셀 이하여야 합니다.");
                } finally {
                    reader.dispose();
                }
            }
            String extension = format.equals("png") ? "png" : "jpg";
            String type = "image/" + format;
            long fileSize = Files.size(file);
            path = storage.put(id, file, extension, type);
            String assetId = path.substring(path.lastIndexOf('/') + 1);
            String url = publicBaseUrl + "/api/v1/studio/assets/" + id + "/" + assetId;
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            tx -> {
                                Project p =
                                        projects.lock(id)
                                                .orElseThrow(
                                                        () ->
                                                                new BusinessException(
                                                                        ErrorCode.NOT_FOUND));
                                p.requireOwner(subject);
                                p.update(p.storePage().withThumbnail(url), version);
                            });
            return new ThumbnailAsset(assetId, url, fileSize, type);
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
}
