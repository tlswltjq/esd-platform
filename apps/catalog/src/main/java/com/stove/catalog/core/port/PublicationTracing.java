package com.stove.catalog.core.port;

import java.util.function.Supplier;

public interface PublicationTracing {
    String capture();
    <T> T call(String traceParent, String requestId, Supplier<T> work);
}
