package com.stove.license.api.controller.dto;

import com.stove.license.core.domain.License;
import org.springframework.data.domain.Page;

import java.util.List;

public record LicensePage(List<LicenseResponse> content, int page, int size, long totalElements, int totalPages) {
    public static LicensePage from(Page<License> page) {
        return new LicensePage(page.map(LicenseResponse::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
