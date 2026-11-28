package com.stove.e2e;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 이 여정이 쓰는 앱만 띄운다. 달지 않으면 {@link StackApp} 전체다. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface LocalStackApps {

    StackApp[] value();
}
