package com.stove.studio.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BuildValidationDispatcherService {
    private final BuildValidationService validationService;

    @Async("buildValidationExecutor")
    public void dispatch(Long buildId) {
        validationService.validate(buildId);
    }
}
