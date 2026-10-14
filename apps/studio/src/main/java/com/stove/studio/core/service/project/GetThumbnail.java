package com.stove.studio.core.service.project;

import com.stove.studio.core.port.BuildStorage;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetThumbnail {
    private final BuildStorage storage;

    public String execute(Long id, String assetId) {
        return storage.thumbnailDownload(id, assetId);
    }
}
