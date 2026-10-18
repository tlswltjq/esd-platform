package com.stove.studio.core.service.project;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.domain.project.StorePage;
import com.stove.studio.core.port.BuildStorage;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional
public class UpdateStorePage {
    private final ProjectRepository projects;
    private final BuildStorage storage;
    private final String publicBaseUrl;

    public Project execute(Long id, String subject, long version, StorePage page) {
        Project p = projects.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        p.requireOwner(subject);
        if (page.thumbnailUrl() != null && !page.thumbnailUrl().isBlank()) {
            String prefix = publicBaseUrl + "/api/v1/studio/assets/" + id + "/";
            if (!page.thumbnailUrl().startsWith(prefix))
                throw new BusinessException(
                        ErrorCode.INVALID_REQUEST, "이 프로젝트에 업로드한 썸네일을 사용해 주세요.");
            storage.thumbnailDownload(id, page.thumbnailUrl().substring(prefix.length()));
        }
        if (page.videoUrl() != null && !page.videoUrl().isBlank()) {
            try {
                URI uri = URI.create(page.videoUrl());
                if (uri.getHost() == null
                        || uri.getUserInfo() != null
                        || !List.of("https", "http").contains(uri.getScheme()))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "영상 URL이 올바르지 않습니다.");
            }
        }
        p.update(page, version);
        return p;
    }
}
