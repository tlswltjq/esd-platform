package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.UploadedPart;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record CompleteUploadRequest(@NotEmpty List<@Valid Part> parts) {

    public List<UploadedPart> toParts() {
        return parts.stream().map(part -> new UploadedPart(part.partNumber(), part.etag())).toList();
    }

    public record Part(@Positive int partNumber, @NotBlank String etag) {
    }
}
