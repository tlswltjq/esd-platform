package com.stove.studio.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 프로젝트 상태머신.
 *
 * <pre>
 * DRAFT ──submit──▶ SUBMITTED ──ReviewApproved──▶ APPROVED
 *                             └─ReviewRejected──▶ REJECTED ──submit──▶ SUBMITTED
 * </pre>
 *
 * <p>전이의 절반은 사람이 부르고(<b>submit</b>) 절반은 이벤트가 부른다(<b>approve/reject</b>).
 * 두 경로의 방어 방식이 달라야 한다 — 사람의 잘못된 요청은 거절해서 알려주는 것이 맞지만,
 * 이벤트는 거절하면 리스너 밖으로 예외가 나가 그 파티션이 멈춘다.
 */
class GameProjectTest {

    private GameProject draftProject() {
        return GameProject.create("GAME-001", "로스트아크", 1001L, 39_000L, "KRW", false);
    }

    private GameProject submittedProject() {
        GameProject project = draftProject();
        project.submit();
        return project;
    }

    @Test
    @DisplayName("초안은 심의를 신청할 수 있다")
    void draftCanBeSubmitted() {
        GameProject project = draftProject();

        project.submit();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.SUBMITTED);
    }

    @Test
    @DisplayName("이미 신청한 프로젝트는 다시 신청할 수 없다")
    void submittedCannotBeSubmittedAgain() {
        GameProject project = submittedProject();

        assertThatThrownBy(project::submit)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 심의 신청된");
    }

    @Test
    @DisplayName("승인된 프로젝트는 다시 신청할 수 없다 — 승인은 종착 상태다")
    void approvedCannotBeSubmittedAgain() {
        GameProject project = submittedProject();
        project.approve("ALL");

        assertThatThrownBy(project::submit)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 승인된");
    }

    @Test
    @DisplayName("반려된 프로젝트는 재신청할 수 있고, 반려 사유는 지워진다")
    void rejectedCanBeResubmitted() {
        GameProject project = submittedProject();
        project.reject("자료 미비");

        project.submit();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.SUBMITTED);
        // 사유를 남겨두면 재신청 뒤에도 화면에 반려가 붙어 있게 된다
        assertThat(project.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("승인은 등급코드를 함께 기록하고 반려 사유를 지운다")
    void approvalRecordsRatingAndClearsRejection() {
        GameProject project = submittedProject();
        project.reject("자료 미비");
        project.submit();

        project.approve("ADULT");

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.APPROVED);
        assertThat(project.getRatingCode()).isEqualTo("ADULT");
        assertThat(project.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("반려는 사유를 남긴다")
    void rejectionRecordsReason() {
        GameProject project = submittedProject();

        project.reject("자료 미비");

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.REJECTED);
        assertThat(project.getRejectReason()).isEqualTo("자료 미비");
    }

    @Test
    @DisplayName("[D-017] 지각 반려는 이미 승인된 프로젝트를 되돌리지 못한다")
    void lateRejectionMustNotDemoteApprovedProject() {
        GameProject project = submittedProject();
        project.approve("ALL");

        project.reject("자료 미비");

        // 수정 전에는 reject() 에 가드가 없어 APPROVED → REJECTED 로 강등됐다.
        // catalog 는 상품을 판매 중인데 스튜디오 화면에만 반려로 보이고,
        // submit() 이 APPROVED 를 막으므로 창작자가 스스로 되돌릴 방법도 없었다.
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.APPROVED);
        assertThat(project.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("[D-017] 신청하지 않은 프로젝트는 승인되지 않는다")
    void approvalWithoutSubmissionIsIgnored() {
        GameProject project = draftProject();

        project.approve("ALL");

        // 심의 결과는 신청한 건에 대해서만 의미가 있다.
        // 수정 전에는 approve() 에 가드가 없어, 이벤트가 잘못 라우팅되면
        // 신청한 적 없는 게임이 그대로 승인 상태가 됐다.
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.DRAFT);
        assertThat(project.getRatingCode()).isNull();
    }

    @Test
    @DisplayName("주인이면 통과한다")
    void ownerPasses() {
        GameProject project = draftProject();

        project.requireOwner(1001L);
    }

    @Test
    @DisplayName("주인이 아니면 막는다")
    void nonOwnerIsRejected() {
        GameProject project = draftProject();

        assertThatThrownBy(() -> project.requireOwner(9999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("판매자 ID 가 없으면 막는다 — 헤더 누락이 소유권 통과가 되면 안 된다")
    void missingSellerIdIsRejected() {
        GameProject project = draftProject();

        assertThatThrownBy(() -> project.requireOwner(null))
                .isInstanceOf(BusinessException.class);
    }
}
