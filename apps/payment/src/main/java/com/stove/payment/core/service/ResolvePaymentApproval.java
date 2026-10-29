package com.stove.payment.core.service;

import com.stove.payment.core.domain.OrderAdmissionFailureReceipt;
import com.stove.payment.core.domain.OrderAdmissionFailure;
import com.stove.payment.core.domain.OrderAdmissionReceipt;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalPostRejected;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentOrderLifecycle;
import com.stove.payment.core.domain.PaymentOrderLifecycleRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.port.ApprovalPayloadProtection;
import com.stove.payment.core.port.OrderPaymentAdmissions;
import com.stove.payment.core.port.PgPaymentApprovalGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public class ResolvePaymentApproval {
    private final PaymentRepository payments;
    private final PaymentOrderLifecycleRepository lifecycle;
    private final PaymentApprovalRepository approvals;
    private final OrderPaymentAdmissions orders;
    private final PgPaymentApprovalGateway gateway;
    private final ApprovalPayloadProtection protection;
    private final ResolvePayment resolve;
    private final Clock clock;
    private final Duration lease;
    private final Duration delay;
    private final int batchSize;
    private final TransactionTemplate transaction;

    public ResolvePaymentApproval(PaymentRepository payments, PaymentApprovalRepository approvals, OrderPaymentAdmissions orders,
            PgPaymentApprovalGateway gateway, ApprovalPayloadProtection protection, ResolvePayment resolve, Clock clock,
            Duration lease, Duration delay, int batchSize, PlatformTransactionManager transactions,PaymentOrderLifecycleRepository lifecycle) {
        if (lease==null || lease.isNegative() || lease.isZero() || delay==null || delay.isNegative() || delay.isZero() || batchSize<1)
            throw new IllegalArgumentException("Bounded approval scheduling is required");
        this.lifecycle=lifecycle; this.payments=payments; this.approvals=approvals; this.orders=orders; this.gateway=gateway;
        this.protection=protection; this.resolve=resolve; this.clock=clock; this.lease=lease; this.delay=delay; this.batchSize=batchSize;
        transaction=new TransactionTemplate(transactions); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public void execute() {
        List<String> due=approvals.due(now(),PageRequest.of(0,batchSize));
        for (String id:due) {
            try { execute(id); }
            catch (RuntimeException failure) { log.warn("Approval resume remains pending: id={}, errorType={}",id,failure.getClass().getSimpleName()); }
        }
    }
    public void execute(String id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Approval HTTP requires no active transaction");
        String token=UUID.randomUUID().toString();
        PaymentApproval claimed=locked(id,(payment,approval)->approval.claim(token,now(),lease)?approval:null);
        if (claimed==null) return;
        try { cycle(id,token,claimed); }
        catch (ApprovalPostRejected rejected) { stop(id,token,"PG_REQUEST_REJECTED"); }
        catch (IllegalArgumentException invalid) { stop(id,token,"PG_INVALID_EVIDENCE"); }
        catch (BusinessException conflict) { stop(id,token,"ORDER_ADMISSION_CONFLICT"); }
        catch (RuntimeException unavailable) {
            log.warn("Approval remains unconfirmed: id={}, errorType={}",id,unavailable.getClass().getSimpleName());
        } finally {
            locked(id,(payment,approval)->approval.finish(token,now(),now().plus(delay)));
        }
    }
    private void cycle(String id, String token, PaymentApproval current) {
        Payment stored=payments.findByPaymentId(current.getPaymentId()).orElseThrow();
        if (stored.getStatus()==PaymentStatus.PAID && current.getAdmittedAt()!=null) {
            locked(id,(payment,approval)->{ if (approval.owns(token,now())) approval.succeed(payment,now()); return null; });
            return;
        }
        if (current.getAdmittedAt()!=null && current.getFailedAt()!=null) {
            completeFailure(id,token,current);
            return;
        }
        if(current.getStatus()==PaymentApprovalStatus.REQUESTED && current.getAdmittedAt()==null && orderExpired(current.getOrderNo())) {
            locked(id,(payment,approval)->{
                if(approval.owns(token,now()) && approval.getAdmittedAt()==null && approval.getStatus()==PaymentApprovalStatus.REQUESTED) {
                    approval.expire(now());
                    if(payment.getStatus()==PaymentStatus.PENDING)payment.expireUnpaid();
                }
                return null;
            });
            return;
        }
        if(current.getAdmittedAt()!=null && orderExpired(current.getOrderNo()))stop(id,token,"ORDER_EXPIRY_CONFLICT");
        ApprovalObservation observed=gateway.inspect(current.purchase());
        if (current.getAdmittedAt()==null) {
            if (observed.state()==ApprovalObservation.State.FAILED) {
                locked(id,(payment,approval)->{ if (approval.owns(token,now())) {
                    approval.failBeforeAdmission(observed.failedAt(),now());
                    if(orderExpired(approval.getOrderNo()) && payment.getStatus()==PaymentStatus.PENDING)payment.expireUnpaid();
                } return null; });
                return;
            }
            if (observed.state()!=ApprovalObservation.State.READY || current.isPostStopped()) {
                if (observed.state()==ApprovalObservation.State.PAID) stop(id,token,"PAID_WITHOUT_ADMISSION");
                return;
            }
            Optional<OrderAdmissionReceipt> receipt=orders.admit(current.admissionRequest());
            if (receipt.isEmpty()) {
                locked(id,(payment,approval)->{
                    if (approval.owns(token,now())) { approval.expire(now()); payment.expireUnpaid(); }
                    return null;
                });
                return;
            }
            current=locked(id,(payment,approval)->{
                if (!approval.owns(token,now())) return null;
                approval.admit(receipt.get()); approval.prepare(now()); return approval;
            });
            if (current==null) return;
        }
        if (completeObservation(id,token,current,observed)) return;
        if (observed.state()!=ApprovalObservation.State.READY || current.isPostStopped()) return;
        PaymentApproval dispatch=locked(id,(payment,approval)->{
            if (!approval.owns(token,now())) return null;
            if (approval.getFirstPreparedAt()==null) approval.prepare(now());
            return approval.canPost(now())?approval:null;
        });
        if (dispatch==null) return;
        String body=protection.open(id,dispatch.getProtectedPayload());
        Boolean permitted=locked(id,(payment,approval)->approval.owns(token,now()) && approval.canPost(now()));
        if (!Boolean.TRUE.equals(permitted)) return;
        gateway.submit(id,dispatch.purchase(),body,dispatch.getFirstPreparedAt(),dispatch.getLeaseUntil());
        completeObservation(id,token,dispatch,gateway.inspect(dispatch.purchase()));
    }
    private boolean completeObservation(String id, String token, PaymentApproval current, ApprovalObservation observed) {
        if (observed.state()==ApprovalObservation.State.PAID) {
            resolve.execute(current.getPaymentId());
            locked(id,(payment,approval)->{
                if (approval.owns(token,now()) && payment.getStatus()==PaymentStatus.PAID) approval.succeed(payment,now());
                return null;
            });
            return true;
        }
        if (observed.state()!=ApprovalObservation.State.FAILED) return false;
        PaymentApproval failed=locked(id,(payment,approval)->{
            if (!approval.owns(token,now())) return null;
            approval.recordFailure(observed.failedAt()); return approval;
        });
        if (failed==null) return true;
        completeFailure(id,token,failed);
        return true;
    }
    private void completeFailure(String id,String token,PaymentApproval failed) {
        OrderAdmissionFailure request=new OrderAdmissionFailure(failed.admissionRequest(),failed.getSupplierAttemptId(),failed.getFailedAt());
        OrderAdmissionFailureReceipt receipt=orders.fail(request);
        locked(id,(payment,approval)->{
            if (approval.owns(token,now())) {
                approval.fail(receipt,now());
                if ("EXPIRED".equals(receipt.orderStatus()) || orderExpired(approval.getOrderNo())) payment.expireUnpaid();
            }
            return null;
        });
    }
    private boolean orderExpired(String orderNo) {
        return lifecycle.findById(orderNo).map(PaymentOrderLifecycle::expired).orElse(false);
    }
    private void stop(String id,String token,String code) {
        locked(id,(payment,approval)->{ if (approval.owns(token,now()) && !approval.terminal()) approval.stopPosts(code); return null; });
    }
    private <T> T locked(String id,BiFunction<Payment,PaymentApproval,T> action) {
        PaymentApproval snapshot=approvals.findByApprovalId(id).orElse(null);
        if (snapshot==null) return null;
        return transaction.execute(tx->{
            Payment payment=payments.lockByPaymentId(snapshot.getPaymentId()).orElseThrow();
            PaymentApproval approval=approvals.lockByApprovalId(id).orElseThrow();
            return action.apply(payment,approval);
        });
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
