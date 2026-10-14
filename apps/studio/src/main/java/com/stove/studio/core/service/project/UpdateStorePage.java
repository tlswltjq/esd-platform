package com.stove.studio.core.service.project;

import com.stove.common.web.error.*;
import com.stove.studio.core.domain.project.*;
import com.stove.studio.core.port.BuildStorage;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;

@Service
@RequiredArgsConstructor
@Transactional
public class UpdateStorePage {
    private final ProjectRepository projects;
    private final BuildStorage storage;
    private final StudioAssetProperties properties;

    public Project execute(Long id, String subject, long version, StorePage page) {
        Project p = projects.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        p.requireOwner(subject);
        if (page.thumbnailUrl() != null && !page.thumbnailUrl().isBlank()) {
            String prefix = properties.publicBaseUrl() + "/api/v1/studio/assets/" + id + "/";
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
                        || !java.util.List.of("https", "http").contains(uri.getScheme()))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "영상 URL이 올바르지 않습니다.");
            }
        }
        p.update(page, version);
        return p;
    }
}
