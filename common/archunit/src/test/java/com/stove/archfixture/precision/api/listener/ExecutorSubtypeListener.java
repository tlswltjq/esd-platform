package com.stove.archfixture.precision.api.listener;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class ExecutorSubtypeListener {
    public void receive(ThreadPoolTaskExecutor executor) { executor.execute(() -> { }); }
}
