package com.stove.studio.core.domain.project;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import com.stove.common.kafka.contract.payload.ProjectWithdrawalResult;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An immutable creator instruction and its durable acceptance/final receipt. */
@Entity
@Getter
@Table(name = "studio_request_command")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRequestCommand extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long projectId;
    @Column(nullable = false, length = 64) private String requestKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ProjectRequestKind kind;
    @Column(nullable = false, length = 100) private String subject;
    @Column(nullable = false) private long contentVersion;
    @Column(nullable = false) private Long targetRequestId;
    @Column(length = 2000) private String reason;
    @Column(nullable = false, length = 20) private String state;
    @Column(length = 20) private String finalStatus;
    @Column(length = 100) private String finalActor;
    @Column(length = 2000) private String finalFeedback;
    private Instant finalizedAt;
    @Column(length = 64) private String sourceRequestEventId;

    public static ProjectRequestCommand submission(Project project, ProjectRequestKind kind,
            SubmissionCommand input, Long targetRequestId) {
        if (kind != ProjectRequestKind.REQUEST_REVIEW && kind != ProjectRequestKind.REQUEST_RELEASE)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "제출 명령 종류가 올바르지 않습니다.");
        ProjectRequestCommand command = base(project, kind, input.requestId(), input.contentVersion(), targetRequestId);
        command.state = "ACCEPTED";
        return command;
    }

    public static ProjectRequestCommand withdrawal(Project project, ProjectRequestKind kind,
            WithdrawalCommand input, Long targetRequestId, long contentVersion) {
        if (kind != ProjectRequestKind.WITHDRAW_REVIEW && kind != ProjectRequestKind.WITHDRAW_RELEASE)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 명령 종류가 올바르지 않습니다.");
        ProjectRequestCommand command = base(project, kind, input.requestId(), contentVersion, targetRequestId);
        command.reason = input.reason();
        command.state = "PENDING";
        return command;
    }

    private static ProjectRequestCommand base(Project project, ProjectRequestKind kind,
            String key, long contentVersion, Long targetRequestId) {
        if (project == null || project.getId() == null || kind == null || targetRequestId == null || targetRequestId <= 0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        ProjectCommandValues.requireRequestKey(key);
        ProjectCommandValues.requireContentVersion(contentVersion);
        ProjectRequestCommand command = new ProjectRequestCommand();
        command.projectId = project.getId();
        command.kind = kind;
        command.requestKey = key;
        command.subject = project.getOwnerSubject();
        command.contentVersion = contentVersion;
        command.targetRequestId = targetRequestId;
        return command;
    }

    public void requireSubmission(String actor, SubmissionCommand input) {
        if (!subject.equals(actor) || !requestKey.equals(input.requestId()) || contentVersion != input.contentVersion())
            throw new BusinessException(ErrorCode.CONFLICT, "같은 요청 식별자의 내용이 다릅니다.");
    }

    public void requireWithdrawal(String actor, Long target, WithdrawalCommand input) {
        if (!subject.equals(actor) || !requestKey.equals(input.requestId()) || !targetRequestId.equals(target)
                || !Objects.equals(reason, input.reason()))
            throw new BusinessException(ErrorCode.CONFLICT, "같은 철회 명령의 내용이 다릅니다.");
    }

    public ProjectWithdrawalRequest instruction(String productCode) {
        return new ProjectWithdrawalRequest(id, targetRequestId, projectId, contentVersion, productCode, subject, reason);
    }

    public void resolve(ProjectWithdrawalResult result) {
        if (result == null || result.actor() == null || result.actor().isBlank()
                || result.actor().length() > 100 || result.finalizedAt() == null
                || result.requestEventId() == null || result.requestEventId().isBlank()
                || result.requestEventId().length() > 64)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 최종 근거가 올바르지 않습니다.");
        if (kind == ProjectRequestKind.WITHDRAW_REVIEW) {
            ProjectCommandValues.requireReviewResult(result.status(), result.actor(), result.feedback(), result.finalizedAt());
        } else if (kind == ProjectRequestKind.WITHDRAW_RELEASE) {
            ProjectCommandValues.requireReleaseResult(result.status(), result.feedback());
        } else {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 명령이 아닙니다.");
        }
        if ("WITHDRAWN".equals(result.status()) && !subject.equals(result.actor()))
            throw new BusinessException(ErrorCode.CONFLICT, "철회 확정 행위자가 다릅니다.");
        if (!id.equals(result.withdrawalId()) || !targetRequestId.equals(result.requestId())
                || !projectId.equals(result.projectId()) || contentVersion != result.contentVersion())
            throw new BusinessException(ErrorCode.CONFLICT, "철회 결과의 대상이 일치하지 않습니다.");
        if (!"PENDING".equals(state)) {
            if (!Objects.equals(finalStatus, result.status()) || !Objects.equals(finalActor, result.actor())
                    || !Objects.equals(finalFeedback, result.feedback()) || !Objects.equals(finalizedAt, result.finalizedAt())
                    || !Objects.equals(sourceRequestEventId, result.requestEventId()))
                throw new BusinessException(ErrorCode.CONFLICT, "확정된 철회 결과와 다릅니다.");
            return;
        }
        state = "WITHDRAWN".equals(result.status()) ? "WITHDRAWN" : "DENIED";
        finalStatus = result.status();
        finalActor = result.actor();
        finalFeedback = result.feedback();
        finalizedAt = result.finalizedAt();
        sourceRequestEventId = result.requestEventId();
    }
}
