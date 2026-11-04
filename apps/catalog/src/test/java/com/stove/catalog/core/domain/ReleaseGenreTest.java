package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.catalog.CatalogFixtures;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReleaseGenreTest {
    @Test void anOtherwiseValidReleaseWithoutGenresIsRejected() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ObjectNode tree = json.valueToTree(CatalogFixtures.event(1, 1).snapshot());
        tree.remove("genres");
        ProjectSnapshot snapshot = json.treeToValue(tree, ProjectSnapshot.class);
        assertThatThrownBy(() -> new ReleaseSubmission("genre-event", "ProjectReleaseRequested", Instant.now(), 1L, 1L, snapshot))
                .isInstanceOf(BusinessException.class);
    }
}
