package com.stove.archfixture.precision.api.scheduler;

import com.stove.archfixture.precision.core.service.ReadRows;

public class WorkScheduler implements Runnable {
    private final ReadRows read;

    public WorkScheduler(ReadRows read) { this.read = read; }
    @Override public void run() { read.execute(); }
}
