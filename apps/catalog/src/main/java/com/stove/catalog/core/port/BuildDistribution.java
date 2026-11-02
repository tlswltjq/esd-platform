package com.stove.catalog.core.port;

import com.stove.catalog.core.domain.DownloadBuildReference;
import java.util.List;

public interface BuildDistribution {
    void requireConfigured();
    void apply(List<DownloadBuildReference> builds, boolean restricted);
}
