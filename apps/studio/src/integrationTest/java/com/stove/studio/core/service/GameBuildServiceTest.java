package com.stove.studio.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.EventType;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.NewBuild;
import com.stove.studio.core.domain.NewProject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 빌드 등록 — 저장과 발행이 한 트랜잭션이라는 것.
 *
 * <p>등록은 프로젝트 애그리거트를 <b>읽고</b> 빌드를 <b>쓴다.</b> 소유권 검사는 프로젝트가,
 * 중복 버전 검사는 빌드가 판정하는데 <b>둘 다 통과한 뒤에만</b> 부수효과(업로드 자리 발급,
 * 이벤트 적재)가 일어나야 한다. 그 순서가 이 클래스가 지키는 성질이다.
 *
 * <p>{@link GameProjectServiceTest} 와 같은 컨텍스트를 쓴다 — 프로젝트 없이 빌드가 존재할 수 없어
 * 준비 과정에서 {@link GameProjectService} 를 그대로 부른다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class GameBuildServiceTest {

    private static final Long SELLER = 1001L;
    private static final Long OTHER_SELLER = 2002L;

    @Autowired
    GameBuildService gameBuildService;
    @Autowired
    GameProjectService gameProjectService;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    private static String uniqueProductCode() {
        return "GAME-" + UUID.randomUUID();
    }

    private GameProject project(String productCode) {
        return gameProjectService.create(
                new NewProject(productCode, "로스트아크", SELLER, 39_000L, "KRW", false));
    }

    private List<OutboxEvent> outboxFor(String productCode) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> productCode.equals(e.getPartitionKey()))
                .toList();
    }

    @Test
    @DisplayName("빌드 등록은 저장 경로를 이벤트와 레코드에 같이 싣는다")
    void uploadRecordsEvent() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);

        GameBuild build = gameBuildService.upload(created.getId(), SELLER,
                new NewBuild("1.0.0", 1_024L, "sha256:abc"));

        // download 가 이 경로로 매니페스트를 만든다. 레코드와 이벤트가 어긋나면
        // 다운로드가 존재하지 않는 파일을 가리킨다.
        assertThat(build.getStoragePath()).contains(productCode).contains("1.0.0");

        List<OutboxEvent> published = outboxFor(productCode);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).getEventType()).isEqualTo(EventType.BUILD_UPLOADED);
        assertThat(published.get(0).getPayload()).contains(build.getStoragePath());
        // 빌드 사건도 프로젝트 스트림에 실린다 — 한 상품의 사건이 한 줄로 늘어서야 한다
        assertThat(published.get(0).getAggregateType()).isEqualTo("GameProject");
    }

    @Test
    @DisplayName("같은 버전은 두 번 등록되지 않는다")
    void duplicateVersionIsRejected() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);
        gameBuildService.upload(created.getId(), SELLER, new NewBuild("1.0.0", 1_024L, "sha256:abc"));

        assertThatThrownBy(() -> gameBuildService.upload(created.getId(), SELLER,
                new NewBuild("1.0.0", 2_048L, "sha256:def")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(outboxFor(productCode)).hasSize(1);
    }

    @Test
    @DisplayName("남의 프로젝트에는 빌드를 올릴 수 없다 — 소유권 판정은 여전히 프로젝트가 한다")
    void uploadRequiresOwnership() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);

        assertThatThrownBy(() -> gameBuildService.upload(created.getId(), OTHER_SELLER,
                new NewBuild("1.0.0", 1_024L, "sha256:abc")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(gameBuildService.findByGame(created.getId())).isEmpty();
        assertThat(outboxFor(productCode)).isEmpty();
    }
}
