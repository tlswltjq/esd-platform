package com.stove.archfixture.precision.core.service;

public class ReadRows {
    private record RowKey(String value) { }

    public String execute() { return new RowKey("row").value(); }
    public static Class<?> helperType() { return RowKey.class; }
}
