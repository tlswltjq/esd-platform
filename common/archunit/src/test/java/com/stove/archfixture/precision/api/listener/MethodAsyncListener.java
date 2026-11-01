package com.stove.archfixture.precision.api.listener;

import org.springframework.scheduling.annotation.Async;

public class MethodAsyncListener {
    @Async public void receive(String input) { }
}
