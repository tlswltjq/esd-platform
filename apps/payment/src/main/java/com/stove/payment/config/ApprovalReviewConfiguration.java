package com.stove.payment.config;

import com.stove.payment.adapter.ApprovalReviewMetrics;
import com.stove.payment.core.domain.ApprovalClosureRepository;
import com.stove.payment.core.domain.ApprovalInvestigationRepository;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.service.ReadApprovalReviews;
import com.stove.payment.core.service.RecordApprovalInvestigation;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApprovalReviewConfiguration {
    @Bean ReadApprovalReviews readApprovalReviews(PaymentApprovalRepository approvals, ApprovalInvestigationRepository investigations,
            ApprovalClosureRepository closures, Clock refundReceptionClock) {
        return new ReadApprovalReviews(approvals, investigations, closures, refundReceptionClock);
    }
    @Bean RecordApprovalInvestigation recordApprovalInvestigation(PaymentRepository payments, PaymentApprovalRepository approvals, ApprovalInvestigationRepository investigations, Clock refundReceptionClock) {
        return new RecordApprovalInvestigation(payments, approvals, investigations, refundReceptionClock);
    }
    @Bean MeterBinder approvalReviewMetrics(ObjectProvider<ReadApprovalReviews> reviews, @Value("${spring.application.name}") String application) {
        return new ApprovalReviewMetrics(() -> reviews.getObject().counts(), application);
    }
}
