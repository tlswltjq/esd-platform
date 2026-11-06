package com.stove.license.api.controller.dto;

import com.stove.license.core.service.StorageItem;
import java.util.List;
import org.springframework.data.domain.Page;

public record StoragePage(List<StorageItemResponse> content, int page, int size, long totalElements, int totalPages) {
    public static StoragePage from(Page<StorageItem> page) {
        return new StoragePage(page.map(StorageItemResponse::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
