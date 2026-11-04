package com.stove.common.kafka.contract.genre;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenreCatalogTest {
    @Test void retirementPreservesEvidenceAndSearchWhileRejectingNewSubmissions() {
        GenreCatalog catalog = new GenreCatalog(List.of(new GenreDefinition("ACTION", "액션", true),
                new GenreDefinition("RHYTHM", "리듬", false)));
        assertThat(catalog.published(List.of("RHYTHM"))).containsExactly("RHYTHM");
        assertThat(catalog.filter(List.of("RHYTHM"))).containsExactly("RHYTHM");
        assertThat(catalog.draft(List.of("RHYTHM"))).containsExactly("RHYTHM");
        assertThatThrownBy(() -> catalog.submission(List.of("RHYTHM"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.definitions().get(1).selectable()).isFalse();
    }

    @Test void draftAndPublishedLimitsDifferAndSelectionsAreImmutableSets() {
        GenreCatalog catalog = GenreCatalog.standard();
        assertThat(catalog.definitions()).hasSize(10);
        assertThat(catalog.draft(List.of())).isEmpty();
        assertThatThrownBy(() -> catalog.published(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.published(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.published(List.of("ACTION", "ACTION"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.published(List.of("UNKNOWN"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.draft(List.of("ACTION", "RPG", "PUZZLE", "RACING"))).isInstanceOf(IllegalArgumentException.class);
        ArrayList<String> input = new ArrayList<>(List.of("RPG", "ACTION"));
        List<String> normalized = catalog.submission(input);
        input.clear();
        assertThat(normalized).containsExactly("ACTION", "RPG");
        assertThatThrownBy(() -> normalized.add("PUZZLE")).isInstanceOf(UnsupportedOperationException.class);
    }
}
