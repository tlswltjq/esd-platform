package com.stove.studio.core.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class ProjectGenreTest {
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test void aCompleteDraftWithoutGenresCannotBeSubmitted() throws Exception {
        Project project = Project.create("genre-draft", "Game", "creator");
        ObjectNode page = json.createObjectNode().put("title", "Game").put("shortDescription", "Summary")
                .put("thumbnailUrl", "https://example.com/cover.png").put("content", "Content").put("price", 1000);
        page.putArray("genres");
        project.update(json.treeToValue(page, StorePage.class), 1);
        project.selectBuild(1L, project.getContentVersion());
        assertThatThrownBy(project::requireReady).isInstanceOf(BusinessException.class);
    }

    @Test void aGenreOnlyEditChangesTheVersionButReorderingDoesNot() throws Exception {
        Project project = Project.create("genre-version", "Game", "creator");
        ObjectNode page = json.createObjectNode().put("title", "Game").put("shortDescription", "Summary")
                .put("content", "Content").put("price", 1000);
        page.putArray("genres").add("ACTION");
        project.update(json.treeToValue(page, StorePage.class), 1);
        long before = project.getContentVersion();
        page.putArray("genres").add("RPG").add("ACTION");
        project.update(json.treeToValue(page, StorePage.class), before);
        assertThat(project.getContentVersion()).isEqualTo(before + 1);
        page.putArray("genres").add("ACTION").add("RPG");
        project.update(json.treeToValue(page, StorePage.class), project.getContentVersion());
        assertThat(project.getContentVersion()).isEqualTo(before + 1);
    }
}
