package com.stove.studio.core.domain.project;

import java.util.List;

public record StorePage(
        String title,
        String shortDescription,
        String thumbnailUrl,
        String videoUrl,
        String content,
        long price, List<String> genres) {
    public StorePage {
        genres = ProjectGenreValues.draft(genres);
    }

    public StorePage withThumbnail(String url) {
        return new StorePage(title, shortDescription, url, videoUrl, content, price, genres);
    }
}
