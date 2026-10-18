package com.stove.download.core.port;

import com.stove.download.core.domain.PublishedBuild;

public interface PublishedBuilds {
    PublishedBuild require(String productCode);
}
