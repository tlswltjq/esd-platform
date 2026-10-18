package com.stove.studio.core.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class ProjectTest {
    @Test
    void projectOwnerAndEditVersionProtectMetadata() {
        Project p = Project.create("GAME", "Game", "creator");
        assertThatThrownBy(() -> p.requireOwner("other")).isInstanceOf(BusinessException.class);
        p.update(new StorePage("Updated", "Summary", null, null, "Content", 1000), 1);
        assertThatThrownBy(() -> p.update(new StorePage("Old", "", null, null, "", 0), 1))
                .isInstanceOf(BusinessException.class);
        assertThat(p.getTitle()).isEqualTo("Updated");
    }

    @Test
    void outgoingRequestsDoNotFreezeMetadataOrCreateAnApprovalOrPublicationState() {
        Project p = Project.create("GAME", "Game", "creator");
        p.linkReviewRequest(10L);
        p.linkReleaseRequest(20L);
        p.update(new StorePage("Updated", "Summary", null, null, "Content", 1000), 1);
        p.selectBuild(11L, 2);
        assertThat(p.getContentVersion()).isEqualTo(3);
        assertThat(p.getCurrentReviewRequestId()).isEqualTo(10L);
        assertThat(p.getCurrentReleaseRequestId()).isEqualTo(20L);
    }
}
