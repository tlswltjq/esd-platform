package com.stove.studio.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UploadSessionTest {

    @Test
    @DisplayName("열린 업로드 세션은 한 번 완료되고 재호출은 멱등이다")
    void completeIsIdempotent() {
        UploadSession session = UploadSession.open(1L, "upload-1", 5L, 2,
                "idem-1", Instant.now().plusSeconds(60));

        session.complete();
        Instant completedAt = session.getCompletedAt();
        session.complete();

        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(session.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("만료된 업로드 세션은 완료할 수 없다")
    void expiredSessionCannotComplete() {
        UploadSession session = UploadSession.open(1L, "upload-1", 5L, 1,
                "idem-1", Instant.now().minusSeconds(1));

        assertThatThrownBy(session::complete).isInstanceOf(BusinessException.class);
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.OPEN);
    }

    @Test
    @DisplayName("정리 작업으로 만료된 세션은 이후 완료할 수 없다")
    void explicitlyExpiredSessionCannotComplete() {
        UploadSession session = UploadSession.open(1L, "upload-1", 5L, 1,
                "idem-1", Instant.now().plusSeconds(60));

        session.expire();

        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
        assertThatThrownBy(session::complete).isInstanceOf(BusinessException.class);
    }
}
