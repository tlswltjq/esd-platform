package com.stove.e2e;

import java.util.stream.Stream;

/**
 * Payment를 검증 전용 장애 설정({@code PaymentFaultConfiguration})으로 띄운다.
 *
 * <p>운영 Payment는 프로세스 안에서 모든 결제를 승인하므로(결정 in-process-pg-approval) 거절이나 응답 유실은 이
 * 설정으로만 만들 수 있다. 통합 테스트 소스셋에서 컴파일되고 bootJar에는 들어가지 않는다. 파이썬 실행기의
 * {@code support/payment_fault.py}와 같은 값이다.
 */
final class PaymentFault {

    static final String DECLINE = "--stove.verification.decline=true";
    static final String LOSE_RESPONSE_AFTER_PROVIDER = "--stove.verification.fail-after-provider=true";
    static final String FAIL_AFTER_OUTBOX = "--stove.verification.fail-after-outbox=true";

    private static final String MAIN_CLASS = "com.stove.payment.PaymentServiceApplication";
    private static final String FAULT_SOURCES =
            "--spring.main.sources=com.stove.payment.verification.PaymentFaultConfiguration";

    private PaymentFault() {
    }

    /** Payment를 내리고 장애 설정을 얹어 다시 띄운다. */
    static void restart(LocalStack stack, String... faults) {
        String[] arguments = Stream.concat(Stream.of(FAULT_SOURCES), Stream.of(faults)).toArray(String[]::new);
        stack.restartFromVerificationClasspath(StackApp.PAYMENT, MAIN_CLASS, arguments);
    }
}
