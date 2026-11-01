package com.stove.archfixture.precision.api.listener;

import java.util.concurrent.Executor;

public class ExecutorListener {
    public void receive(Executor executor) { executor.execute(() -> { }); }
}
