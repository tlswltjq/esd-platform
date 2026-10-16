package com.stove.payment.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.messaging.outbox.*;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.PgGateway;
import com.stove.payment.infrastructure.pg.SimulatedPgGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.*;

/** Explicit OCI verification source; absent from bootJar and production component scanning. */
@TestConfiguration(proxyBeanMethods=false)
public class PaymentFaultConfiguration {
    @Bean @Primary PgGateway verificationGateway(SimulatedPgGateway supplier,Environment env) {
        return request -> {
            PgResult result=supplier.charge(request);
            if(env.getProperty("stove.verification.fail-after-provider",Boolean.class,false))
                throw new PgUnavailable("verification: response lost after supplier commit");
            return result;
        };
    }
    @Bean @Primary OutboxRecorder verificationOutbox(OutboxEventRepository repository,ObjectMapper mapper,Environment env) {
        return new FaultOutbox(repository,mapper,env.getProperty("stove.verification.fail-after-outbox",Boolean.class,false));
    }
    public static class FaultOutbox extends OutboxRecorder {
        private final boolean fail;
        FaultOutbox(OutboxEventRepository repository,ObjectMapper mapper,boolean fail) { super(repository,mapper); this.fail=fail; }
        @Override @Transactional(propagation=Propagation.MANDATORY)
        public void record(String type,String id,DomainEvent event) {
            super.record(type,id,event);
            if(fail) throw new IllegalStateException("verification: rollback after Outbox insert");
        }
    }
}
