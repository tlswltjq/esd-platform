package com.stove.common.kafka.contract.genre;

public record GenreDefinition(String code, String label, boolean selectable) {
    public GenreDefinition {
        if (code == null || !code.matches("[A-Z][A-Z_]{0,31}") || label == null || label.isBlank())
            throw new IllegalArgumentException("Invalid genre definition");
    }
}
