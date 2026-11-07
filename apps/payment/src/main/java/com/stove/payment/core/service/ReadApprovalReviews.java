package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.ApprovalReviewLookup;
import com.stove.payment.core.domain.ApprovalReviewQuery;
import com.stove.payment.core.domain.ApprovalReviewView;
import com.stove.payment.core.domain.ApprovalReviewReason;
import com.stove.payment.core.domain.ApprovalInvestigation;
import com.stove.payment.core.domain.ApprovalInvestigationRepository;
import com.stove.payment.core.domain.ApprovalInvestigationView;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadApprovalReviews {
    private final PaymentApprovalRepository approvals;
    private final ApprovalInvestigationRepository investigations;
    private final Clock clock;
    public long count(String reason) {
        if (!"OTHER".equals(reason)) return approvals.countByStatusAndLastErrorCode(PaymentApprovalStatus.REVIEW_REQUIRED, reason);
        long known = 0;
        for (String code : ApprovalReviewReason.ALL) if (!"OTHER".equals(code)) known += approvals.countByStatusAndLastErrorCode(PaymentApprovalStatus.REVIEW_REQUIRED, code);
        return approvals.countByStatus(PaymentApprovalStatus.REVIEW_REQUIRED) - known;
    }
    public Page<ApprovalReviewView> search(ApprovalReviewQuery query) {
        PageRequest page = PageRequest.of(query.page(), query.size());
        return (query.reason() == null ? approvals.findReviews(page) : approvals.findReviewsByReason(query.reason(), ApprovalReviewReason.ALL.subList(0, 5), page)).map(this::view);
    }
    public ApprovalReviewView read(ApprovalReviewLookup lookup) { return view(find(lookup)); }
    public Page<ApprovalInvestigationView> history(ApprovalReviewLookup lookup, ApprovalReviewQuery query) {
        find(lookup);
        return investigations.findByApprovalId(lookup.approvalId(), PageRequest.of(query.page(), query.size(), Sort.by("recordedAt", "id"))).map(ApprovalInvestigation::view);
    }
    private PaymentApproval find(ApprovalReviewLookup lookup) {
        return approvals.findByApprovalId(lookup.approvalId()).filter(a -> a.getStatus() == PaymentApprovalStatus.REVIEW_REQUIRED).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    private ApprovalReviewView view(PaymentApproval a) {
        return new ApprovalReviewView(a.getApprovalId(), a.getOrderNo(), a.getPaymentId(), a.getLastErrorCode(), a.getAdmittedAt() != null,
                a.getReceivedAt(), a.getAdmittedAt(), a.getFirstPreparedAt(), a.getReviewStartedAt(), a.getReviewStartedAt() == null ? null : Math.max(0, Duration.between(a.getReviewStartedAt(), clock.instant()).getSeconds()),
                a.getLatestPgState(), a.getLatestPgObservedAt(), investigations.countByApprovalId(a.getApprovalId()));
    }
}
