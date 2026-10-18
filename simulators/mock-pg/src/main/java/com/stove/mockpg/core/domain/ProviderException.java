package com.stove.mockpg.core.domain;

public class ProviderException extends RuntimeException {
    private final int status;
    public ProviderException(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
