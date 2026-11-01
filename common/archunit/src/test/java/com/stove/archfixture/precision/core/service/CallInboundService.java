package com.stove.archfixture.precision.core.service;

import com.stove.archfixture.precision.api.scheduler.WorkScheduler;

public class CallInboundService {
    public void execute(ReadRows read) { new WorkScheduler(read).run(); }
}
