package com.stove.studio.core.domain;

import java.util.List;

public record MultipartUploadTicket(
        String storagePath,
        String storageUploadId,
        long partSize,
        List<UploadPartUrl> parts
) {
}
