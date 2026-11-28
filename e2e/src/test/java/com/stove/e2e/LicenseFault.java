package com.stove.e2e;

import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * License를 검증 전용 장애 설정({@code LicenseFaultConfiguration})으로 띄운다. 통합 테스트 소스셋에서 컴파일되고
 * bootJar에는 들어가지 않는다.
 */
final class LicenseFault {

    /** 지급을 커밋한 뒤 ACK 전에 프로세스를 내린다(종료 코드 91). */
    static final String HALT_AFTER_COMMIT = "--stove.verification.halt-after-commit=true";
    static final int HALT_EXIT_CODE = 91;

    private static final String MAIN_CLASS = "com.stove.license.LicenseServiceApplication";
    private static final String FAULT_SOURCES =
            "--spring.main.sources=com.stove.license.verification.LicenseFaultConfiguration";

    private LicenseFault() {
    }

    /** 커밋한 이벤트 ID를 적을 파일. 받은 이벤트 ID는 {@code <파일>.deliveries}에 한 줄씩 쌓인다. */
    static String commitMarker(Path marker) {
        return "--stove.verification.commit-marker=" + marker;
    }

    /** License를 내리고 장애 설정을 얹어 다시 띄운다. */
    static void restart(LocalStack stack, String... faults) {
        String[] arguments = Stream.concat(Stream.of(FAULT_SOURCES), Stream.of(faults)).toArray(String[]::new);
        stack.restartFromVerificationClasspath(StackApp.LICENSE, MAIN_CLASS, arguments);
    }
}
