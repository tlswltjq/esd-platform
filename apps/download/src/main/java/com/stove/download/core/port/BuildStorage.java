package com.stove.download.core.port;

import com.stove.download.core.domain.PublishedBuild;
import com.stove.download.core.domain.SignedUrl;

public interface BuildStorage {
    SignedUrl download(PublishedBuild build);
}
