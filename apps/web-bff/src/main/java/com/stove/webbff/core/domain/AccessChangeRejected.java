package com.stove.webbff.core.domain;

/**
 * 계정 접근 변경 사실 자체가 잘못됐다(헤더·역직렬화·계약 위반). 다시 처리해도 결과가 같은 영구 실패라 재시도 없이 DLT로 간다
 * (결정 226). 저장소 장애는 이것이 아니다 — {@code block} 시간 초과의 {@code IllegalStateException}처럼 같은 종류의 예외라도
 * 처리 단계에서 나면 일시 실패로 성공할 때까지 재시도한다(PR #246 R1).
 */
public class AccessChangeRejected extends RuntimeException {

    public AccessChangeRejected(String message, Throwable cause) {
        super(message, cause);
    }
}
