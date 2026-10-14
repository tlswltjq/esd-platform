package com.stove.studio.core.port;

import java.nio.file.Path;

public interface BuildStorage {
    String put(Long projectId, Path file, String extension, String contentType);

    long size(String path);

    void delete(String path);

    String thumbnailDownload(Long projectId, String assetId);
}
