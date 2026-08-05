package com.stove.archfixture.compliant.api.controller;

import org.springframework.web.bind.annotation.RestController;

/** 준수 픽스처. */
@RestController
public class CompliantController {

    private final Object collaborator;

    public CompliantController(Object collaborator) {
        this.collaborator = collaborator;
    }

    public void handle() {
        collaborator.toString();
    }
}
