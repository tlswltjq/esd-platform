package com.stove.catalog.adapter.storage;

import com.stove.catalog.core.domain.DownloadBuildReference;
import com.stove.catalog.core.port.BuildDistribution;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.List;

public class UnavailableBuildDistribution implements BuildDistribution {
    @Override public void requireConfigured() { throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "다운로드 저장소 차단이 구성되지 않았습니다."); }
    @Override public void apply(List<DownloadBuildReference> builds, boolean restricted) { requireConfigured(); }
}
