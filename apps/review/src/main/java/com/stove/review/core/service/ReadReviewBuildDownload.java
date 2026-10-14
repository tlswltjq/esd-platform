package com.stove.review.core.service;

import com.stove.review.core.domain.BuildDownload;
import com.stove.review.core.port.ReviewBuildStorage;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

/** Storage access happens after the short query transaction has finished. */
@Service
@RequiredArgsConstructor
public class ReadReviewBuildDownload {
    private final ReadProjectReviews read;
    private final ReviewBuildStorage storage;

    public BuildDownload execute(Long id) {
        return storage.download(read.snapshot(read.get(id)));
    }
}
