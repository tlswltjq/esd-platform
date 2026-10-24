package com.stove.payment.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.messaging.trace.TraceContextCapture;
import com.stove.payment.adapter.pg.SimulatedPgHttpGateway;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.PgGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit local verification source; absent from bootJar and production component scanning. */
@TestConfiguration(proxyBeanMethods=false)
public class PaymentFaultConfiguration {
    @Bean @Primary PgGateway verificationGateway(SimulatedPgHttpGateway supplier,Environment env) {
        return request -> {
            PgResult result=supplier.charge(request);
            if(env.getProperty("stove.verification.fail-after-provider",Boolean.class,false))
                throw new PgUnavailable("verification: response lost after supplier commit");
            return result;
        };
    }
    @Bean @Primary OutboxRecorder verificationOutbox(OutboxEventRepository repository,ObjectMapper mapper,Environment env,
            TraceContextCapture traceContextCapture) {
        return new FaultOutbox(repository,mapper,traceContextCapture,env.getProperty("stove.verification.fail-after-outbox",Boolean.class,false));
    }
    public static class FaultOutbox extends OutboxRecorder {
        private final boolean fail;
        FaultOutbox(OutboxEventRepository repository,ObjectMapper mapper,TraceContextCapture capture,boolean fail) {
            super(repository,mapper,capture); this.fail=fail;
        }
        @Override @Transactional(propagation=Propagation.MANDATORY)
        public void record(String type,String id,DomainEvent event) {
            super.record(type,id,event);
            if(fail) throw new IllegalStateException("verification: rollback after Outbox insert");
        }
    }
}
