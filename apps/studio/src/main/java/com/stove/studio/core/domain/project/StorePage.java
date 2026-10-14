package com.stove.studio.core.domain.project;

public record StorePage(
        String title,
        String shortDescription,
        String thumbnailUrl,
        String videoUrl,
        String content,
        long price) {
    public StorePage withThumbnail(String url) {
        return new StorePage(title, shortDescription, url, videoUrl, content, price);
    }
}
