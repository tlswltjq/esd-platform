package com.stove.review.core.domain;

import java.time.Instant;

public record BuildDownload(String url, Instant expiresAt, long fileSize, String checksum) {}
