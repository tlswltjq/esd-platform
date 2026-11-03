package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.CatalogPublicationJob;
import com.stove.catalog.core.domain.CatalogPublicationJobRepository;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadPublicationJobs {
    private final CatalogPublicationJobRepository jobs;

    public List<CatalogPublicationJob> list(int page, int size) {
        return jobs.findByStateOrderByCreatedAtAscRequestIdAsc("PENDING",
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size))));
    }

    public CatalogPublicationJob get(Long requestId) {
        return jobs.findById(requestId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
