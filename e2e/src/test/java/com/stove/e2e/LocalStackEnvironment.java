package com.stove.e2e;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 여정의 스택이 모든 앱에 처음부터 주는 환경 변수({@code "이름=값"}). 스택이 정하는 값보다 앞선다. 운영 기본값으로는
 * 여정이 너무 오래 걸리거나(주기), 기본 스택에 없는 연결(추가 저장소 계정)이 필요할 때만 쓴다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface LocalStackEnvironment {

    String[] value();
}
