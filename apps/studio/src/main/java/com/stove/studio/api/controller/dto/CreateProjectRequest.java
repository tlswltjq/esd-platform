package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,49}") String productCode,
        @NotBlank @Size(max = 200) String title) {}
