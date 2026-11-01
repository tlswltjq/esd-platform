package com.stove.archfixture.precision.core.service;

import com.stove.archfixture.precision.core.domain.InternalInput;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ReceiveInputs {
    public void direct(DomainEvent event) { }
    public void list(List<DomainEvent> events) { }
    public void array(DomainEvent[] events) { }
    public void nested(Map<String, Optional<List<DomainEvent>>> events) { }
    public void wildcard(List<? extends DomainEvent> events) { }
    public void lowerBound(List<? super DomainEvent> events) { }
    public <T extends DomainEvent> void bounded(T event) { }
    public <T extends Comparable<T>> void recursiveBound(T input) { }
    public void genericArray(List<DomainEvent>[] events) { }
    public void internal(List<InternalInput> inputs) { }
    public void snapshot(List<ProjectSnapshot> snapshots) { }
    public DomainEvent publish(InternalInput input) { return null; }
}
