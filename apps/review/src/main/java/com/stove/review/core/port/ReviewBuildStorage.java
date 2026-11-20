package com.stove.review.core.port;

import com.stove.common.contract.payload.ProjectSnapshot;
import com.stove.review.core.domain.BuildDownload;

public interface ReviewBuildStorage {
    BuildDownload download(ProjectSnapshot snapshot);
}
