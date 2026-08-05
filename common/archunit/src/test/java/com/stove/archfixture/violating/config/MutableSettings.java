package com.stove.archfixture.violating.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 위반 픽스처 — 설정값인데 이름이 {@code Properties} 로 끝나지 않고 record 도 아니다.
 *
 * <p>setter 가 달린 설정은 런타임에 조용히 바뀌는 전역 가변 상태다.
 */
@ConfigurationProperties(prefix = "fixture.mutable")
public class MutableSettings {

    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
