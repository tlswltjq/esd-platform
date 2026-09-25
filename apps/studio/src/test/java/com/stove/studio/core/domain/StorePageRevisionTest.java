package com.stove.studio.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StorePageRevisionTest {
    @Test
    @DisplayName("상점 draft는 preview 데이터를 갱신한 뒤 발행하면 불변이 된다")
    void draftBecomesImmutableRevision() {
        StorePageRevision revision = StorePageRevision.create(1L, 1, content("초안"), serialized(), true);

        revision.updateDraft(content("수정본"), serialized());
        revision.publish();

        assertThat(revision.getTitle()).isEqualTo("수정본");
        assertThat(revision.getStatus()).isEqualTo(StorePageRevisionStatus.PUBLISHED);
        assertThat(revision.getPublishedAt()).isNotNull();
        assertThatThrownBy(() -> revision.updateDraft(content("바꿔치기"), serialized()))
                .isInstanceOf(BusinessException.class);
    }

    private StorePageContent content(String title) {
        return new StorePageContent(title, "소개", "상세", Map.of(), List.of("RPG"), List.of("인디"),
                "개발사", "배급사", List.of(), List.of(), null, null, List.of("ko"),
                "WINDOWS", "Windows 10", "Windows 11", List.of("싱글플레이"),
                "", "", "", List.of("KR"), Map.of("KRW", 10_000L));
    }

    private StorePageRevision.SerializedContent serialized() {
        return new StorePageRevision.SerializedContent("{}", "[]", "[]", "[]", "[]",
                "[]", "[]", "[\"KR\"]", "{\"KRW\":10000}");
    }
}
