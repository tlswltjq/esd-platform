package com.stove.auth.core.port;

public interface TokenRevocationPort {
    void revokeSubject(String subject);
}
