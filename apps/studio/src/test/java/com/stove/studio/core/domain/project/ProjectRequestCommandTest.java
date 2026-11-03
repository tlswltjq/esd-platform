package com.stove.studio.core.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProjectRequestCommandTest {
    @Test
    void submissionAndWithdrawalKindsCannotBeInterchanged() {
        Project project = project();
        assertThatThrownBy(() -> ProjectRequestCommand.submission(project,
                ProjectRequestKind.WITHDRAW_REVIEW, new SubmissionCommand("key", 2), 10L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ProjectRequestCommand.withdrawal(project,
                ProjectRequestKind.REQUEST_RELEASE, new WithdrawalCommand("key", null), 10L, 2))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void pendingIsNotAnAuthoritativeFinalResult() {
        ProjectRequestCommand command = command();
        assertThatThrownBy(() -> command.resolve(result("REQUESTED", null)))
                .isInstanceOf(BusinessException.class);
        assertThat(command.getState()).isEqualTo("PENDING");
    }

    @Test
    void optionalReasonAndAWithdrawalReceiptAreStableAcrossRetries() {
        assertThat(new WithdrawalCommand("key", "  ").reason()).isNull();
        ProjectRequestCommand command = command();
        ProjectWithdrawalResult result = result("WITHDRAWN", null);
        command.resolve(result);
        command.resolve(result);
        assertThat(command.getState()).isEqualTo("WITHDRAWN");
        assertThat(command.getFinalizedAt()).isEqualTo(result.finalizedAt());
        assertThatThrownBy(() -> command.requireWithdrawal("owner", 11L, new WithdrawalCommand("key", null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> command.resolve(result("REJECTED", "different result")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void oldSubmissionKeysKeepTheirOriginalVersionAndTarget() {
        ProjectRequestCommand command = ProjectRequestCommand.submission(project(),
                ProjectRequestKind.REQUEST_REVIEW, new SubmissionCommand("original", 2), 10L);
        command.requireSubmission("owner", new SubmissionCommand("original", 2));
        assertThat(command.getTargetRequestId()).isEqualTo(10L);
        assertThatThrownBy(() -> command.requireSubmission("owner", new SubmissionCommand("original", 3)))
                .isInstanceOf(BusinessException.class);
    }

    private Project project() {
        Project project = Project.create("GAME", "Game", "owner");
        ReflectionTestUtils.setField(project, "id", 1L);
        return project;
    }

    private ProjectRequestCommand command() {
        ProjectRequestCommand command = ProjectRequestCommand.withdrawal(project(),
                ProjectRequestKind.WITHDRAW_REVIEW, new WithdrawalCommand("key", null), 10L, 2);
        ReflectionTestUtils.setField(command, "id", 20L);
        return command;
    }

    private ProjectWithdrawalResult result(String status, String feedback) {
        return new ProjectWithdrawalResult(20L, 10L, 1L, 2, "GAME", status, "owner", feedback,
                Instant.parse("2026-11-02T03:00:00Z"), "original-event");
    }
}
