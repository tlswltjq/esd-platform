package com.stove.download.core.domain;

public final class DownloadValues {
    private DownloadValues() {}

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    public static boolean subject(String value) { return text(value, 100); }

    public static boolean buildVersion(String value) { return text(value, 100); }

    public static boolean storageReference(String value) { return text(value, 2000); }

    public static boolean productCode(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}");
    }

    public static boolean checksum(String value) {
        return value != null && value.matches("[a-fA-F0-9]{64}");
    }
}
