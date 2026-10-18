package com.stove.download.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.download.core.domain.*;
import com.stove.download.core.port.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReadBuildDownload {
    private final OwnedProducts ownedProducts;
    private final PublishedBuilds publishedBuilds;
    private final BuildStorage storage;

    public DownloadTicket execute(DownloadRequest request, String accessToken) {
        OwnedProduct owned = ownedProducts.require(request.productCode(), accessToken);
        if (!request.productCode().equals(owned.productCode())) throw unavailable();
        PublishedBuild build = publishedBuilds.require(request.productCode());
        if (owned.productId() != build.productId()
                || !request.productCode().equals(build.productCode())) throw unavailable();
        return DownloadTicket.of(build, storage.download(build));
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "상품 연결 정보가 일치하지 않습니다.");
    }
}
