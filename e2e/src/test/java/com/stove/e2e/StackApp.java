package com.stove.e2e;

import java.util.Locale;

/** 일회용 스택에 띄우는 앱과 그 기본 포트. 실제 포트는 여기에 오프셋을 더한 값이다({@link Stove}). */
enum StackApp {
    AUTH("auth", 8091),
    STUDIO("studio", 8085),
    WEB_BFF("web-bff", 8080),
    GATEWAY("gateway", 8093),
    REVIEW("review", 8086),
    CATALOG("catalog", 8081),
    STORE("store", 8087),
    ORDER("order", 8082),
    PAYMENT("payment", 8083),
    LICENSE("license", 8084),
    DOWNLOAD("download", 8088),
    SETTLEMENT("settlement", 8089);

    private final String module;
    private final int port;

    StackApp(String module, int port) {
        this.module = module;
        this.port = port;
    }

    /** {@code apps/<module>} 디렉터리 이름. */
    String module() {
        return module;
    }

    int port() {
        return port;
    }

    /** 다른 앱이 이 앱의 주소를 받는 환경 변수 이름. */
    String uriVariable() {
        return module.toUpperCase(Locale.ROOT).replace('-', '_') + "_URI";
    }
}
