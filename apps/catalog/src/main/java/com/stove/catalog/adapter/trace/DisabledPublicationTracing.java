package com.stove.catalog.adapter.trace;

import com.stove.catalog.core.port.PublicationTracing;
import java.util.function.Supplier;

public class DisabledPublicationTracing implements PublicationTracing {
    @Override public String capture() { return null; }
    @Override public <T> T call(String traceParent, String requestId, Supplier<T> work) { return work.get(); }
}
