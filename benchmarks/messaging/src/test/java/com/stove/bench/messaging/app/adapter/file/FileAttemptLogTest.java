package com.stove.bench.messaging.app.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.bench.messaging.app.core.domain.Attempt;
import com.stove.bench.messaging.app.core.domain.Attempt.Outcome;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAttemptLogTest {

    @TempDir
    Path runDir;

    @Test
    void 기록은_메모리에_쌓였다가_내보낼_때_파일에_덧붙는다() throws Exception {
        // 주기를 길게 둬 배경 스레드가 끼어들지 않게 한다.
        FileAttemptLog log = new FileAttemptLog(runDir, "p1", 3_600_000);
        log.record(new Attempt("e-1", 1, 0, 5L, 10L, 20L, Outcome.FAILED, "BenchInjectedFailure"));

        assertThat(Files.readAllLines(log.file())).containsExactly(Attempt.CSV_HEADER);
        assertThat(log.flush()).isEqualTo(1);

        log.record(new Attempt("e-1", 2, 0, 5L, 30L, 40L, Outcome.APPLIED, ""));
        log.close();

        assertThat(Files.readAllLines(log.file())).containsExactly(
                Attempt.CSV_HEADER,
                "e-1,1,0,5,10,20,FAILED,BenchInjectedFailure",
                "e-1,2,0,5,30,40,APPLIED,");
    }

    @Test
    void 쓰기가_실패하면_묶음을_버리지_않고_다음에_끊긴_줄을_닫은_뒤_다시_쓴다() throws Exception {
        FileAttemptLog log = new FileAttemptLog(runDir, "p1", 3_600_000);
        log.record(new Attempt("e-1", 1, 0, 5L, 10L, 20L, Outcome.APPLIED, ""));
        // 파일이 사라지면 APPEND 쓰기가 실패한다(디스크 오류 대역).
        Files.delete(log.file());

        assertThatThrownBy(log::flush).isInstanceOf(UncheckedIOException.class);
        assertThat(log.writeFailures()).isEqualTo(1);

        Files.writeString(log.file(), "e-0,1,0,4,1");  // 끊긴 줄
        log.record(new Attempt("e-2", 1, 0, 6L, 30L, 40L, Outcome.APPLIED, ""));
        assertThat(log.flush()).isEqualTo(2);
        log.close();

        assertThat(Files.readAllLines(log.file())).containsExactly(
                "e-0,1,0,4,1", "e-1,1,0,5,10,20,APPLIED,", "e-2,1,0,6,30,40,APPLIED,");
    }

    @Test
    void 프로세스마다_다른_파일에_쓰고_같은_이름이면_덮지_않는다() {
        FileAttemptLog first = new FileAttemptLog(runDir, "p1", 3_600_000);
        first.close();

        assertThat(first.file().getFileName().toString()).isEqualTo("attempts-p1.csv");
        assertThatThrownBy(() -> new FileAttemptLog(runDir, "p1", 3_600_000)).isInstanceOf(UncheckedIOException.class);
    }
}
