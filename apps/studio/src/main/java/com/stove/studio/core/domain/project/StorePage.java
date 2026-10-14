package com.stove.studio.core.domain.project;

public record StorePage(
        String title,
        String shortDescription,
        String thumbnailUrl,
        String videoUrl,
        String content,
        long price) {}
