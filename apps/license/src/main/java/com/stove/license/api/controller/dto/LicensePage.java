package com.stove.license.api.controller.dto;

import com.stove.license.core.domain.LicenseRegistration;
import java.util.List;
import org.springframework.data.domain.Page;

public record LicensePage(List<LicenseResponse> content, int page, int size, long totalElements, int totalPages) {
    public static LicensePage from(Page<LicenseRegistration> page) {
        return new LicensePage(page.map(LicenseResponse::from).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
