package com.stove.studio.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.service.build.RegisterBuild;
import com.stove.studio.core.service.project.ReadProjects;

import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/projects/{id}/builds")
public class BuildController {
    private final RegisterBuild registerBuild;
    private final ReadProjects readProjects;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Build> upload(
            @PathVariable Long id,
            @CurrentSubject String subject,
            @RequestParam @NotBlank @Size(max = 100) String version,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        try (var input = file.getInputStream()) {
            return ApiResponse.ok(
                    registerBuild.execute(id, subject, version, file.getOriginalFilename(), input));
        }
    }

    @GetMapping
    public ApiResponse<List<Build>> list(@PathVariable Long id, @CurrentSubject String subject) {
        return ApiResponse.ok(readProjects.builds(id, subject));
    }
}
