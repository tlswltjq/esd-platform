package com.stove.auth.api.controller.dto;

import com.stove.auth.core.domain.UserAccount;

public record SignupResponse(String subject, String email) {

    public static SignupResponse from(UserAccount account) {
        return new SignupResponse(account.getSubject(), account.getEmail());
    }
}
