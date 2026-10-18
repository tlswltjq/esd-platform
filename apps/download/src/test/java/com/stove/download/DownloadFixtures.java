package com.stove.download;

import com.stove.download.core.domain.PublishedBuild;

public final class DownloadFixtures {
    private DownloadFixtures() {}
    public static PublishedBuild build() {
        return new PublishedBuild(1, "game-one", 2, 3, 4, "1.0", 10, "a".repeat(64), "s3://stove-builds/game.zip");
    }
}
