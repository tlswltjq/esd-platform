package com.stove.studio.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.service.build.RegisterBuild;
import com.stove.studio.core.service.project.ReadProjects;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
        try (InputStream input = file.getInputStream()) {
            return ApiResponse.ok(
                    registerBuild.execute(id, subject, version, file.getOriginalFilename(), input));
        }
    }

    @GetMapping
    public ApiResponse<List<Build>> list(@PathVariable Long id, @CurrentSubject String subject) {
        return ApiResponse.ok(readProjects.builds(id, subject));
    }
}
