package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.UploadPartUrl;

public record UploadPartResponse(int partNumber, String uploadUrl) {
    public static UploadPartResponse from(UploadPartUrl part) {
        return new UploadPartResponse(part.partNumber(), part.uploadUrl());
    }
}
