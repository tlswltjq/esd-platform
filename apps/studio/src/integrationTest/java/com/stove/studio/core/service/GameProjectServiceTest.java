package com.stove.studio.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.EventType;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.GameProjectRepository;
import com.stove.studio.core.domain.NewProject;
import com.stove.studio.core.domain.ProjectStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 프로젝트 상태머신 — 생성 · 심의 신청 · 심의 결과 반영.
 *
 * <p>이 서비스에는 테스트가 없었다. 컨트롤러 테스트 6건이 <b>전부 400 으로 끝나는 경로</b>라
 * 핸들러 본문에 닿지 않았고, 리스너 테스트는 서비스를 mock 으로 세웠다.
 *
 * <p>여기서 지킬 성질은 <b>가드를 통과한 뒤에만 부수효과가 일어나는가</b>이다 —
 * 소유권·상태 검사에서 걸린 요청이 이벤트를 남기면 안 된다.
 * 빌드 쪽 성질은 {@link GameBuildServiceTest} 가 본다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class GameProjectServiceTest {

    private static final Long SELLER = 1001L;
    private static final Long OTHER_SELLER = 2002L;

    @Autowired
    GameProjectService gameProjectService;
    @Autowired
    GameProjectRepository projectRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    ProcessedEventRepository processedEventRepository;

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

    private ProjectStatus statusOf(String productCode) {
        return projectRepository.findByProductCode(productCode).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("프로젝트는 초안으로 만들어진다")
    void createProject() {
        String productCode = uniqueProductCode();

        GameProject created = project(productCode);

        assertThat(created.getStatus()).isEqualTo(ProjectStatus.DRAFT);
        assertThat(created.getProductCode()).isEqualTo(productCode);
        // 생성만으로는 아무 이벤트도 나가지 않는다. 심의 신청이 파이프라인의 시작점이다.
        assertThat(outboxFor(productCode)).isEmpty();
    }

    @Test
    @DisplayName("같은 상품코드는 두 번 만들 수 없다 — 서비스 경계를 넘는 자연 키다")
    void duplicateProductCodeIsRejected() {
        String productCode = uniqueProductCode();
        project(productCode);

        assertThatThrownBy(() -> project(productCode))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("심의 신청은 상태를 바꾸고 GameRegistered 를 적재한다")
    void submitForReviewRecordsEvent() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);

        gameProjectService.submitForReview(created.getId(), SELLER);

        assertThat(statusOf(productCode)).isEqualTo(ProjectStatus.SUBMITTED);

        List<OutboxEvent> published = outboxFor(productCode);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).getEventType()).isEqualTo(EventType.GAME_REGISTERED);
        assertThat(published.get(0).getAggregateType()).isEqualTo("GameProject");
        // 파티션 키가 productCode 라는 것이 review·catalog 쪽 순서 보장의 전제다
        assertThat(published.get(0).getAggregateId()).isEqualTo(productCode);
    }

    @Test
    @DisplayName("남의 프로젝트는 신청할 수 없고, 막혔으면 이벤트도 없다")
    void submitRequiresOwnership() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);

        assertThatThrownBy(() -> gameProjectService.submitForReview(created.getId(), OTHER_SELLER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(statusOf(productCode)).isEqualTo(ProjectStatus.DRAFT);
        assertThat(outboxFor(productCode)).isEmpty();
    }

    @Test
    @DisplayName("이미 신청한 프로젝트를 다시 신청하면 이벤트가 두 번 나가지 않는다")
    void resubmitDoesNotDuplicateEvent() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);
        gameProjectService.submitForReview(created.getId(), SELLER);

        assertThatThrownBy(() -> gameProjectService.submitForReview(created.getId(), SELLER))
                .isInstanceOf(BusinessException.class);

        // 상태 가드가 outboxRecorder.record 앞에 있다는 것이 여기서 지킬 순서다
        assertThat(outboxFor(productCode)).hasSize(1);
    }

    @Test
    @DisplayName("없는 프로젝트를 신청하면 NOT_FOUND")
    void submitUnknownProject() {
        assertThatThrownBy(() -> gameProjectService.submitForReview(999_999_999L, SELLER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("승인 이벤트를 반영한다")
    void applyApproval() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);
        gameProjectService.submitForReview(created.getId(), SELLER);

        gameProjectService.applyApproval(UUID.randomUUID().toString(),
                EventType.REVIEW_APPROVED, productCode, "ALL");

        GameProject updated = projectRepository.findByProductCode(productCode).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ProjectStatus.APPROVED);
        assertThat(updated.getRatingCode()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("같은 승인 이벤트를 다시 받아도 마킹이 남아 재처리되지 않는다")
    void applyApprovalIsGuardedByInbox() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);
        gameProjectService.submitForReview(created.getId(), SELLER);
        String eventId = UUID.randomUUID().toString();

        gameProjectService.applyApproval(eventId, EventType.REVIEW_APPROVED, productCode, "ALL");
        gameProjectService.applyApproval(eventId, EventType.REVIEW_APPROVED, productCode, "ADULT");

        // 두 번째 호출이 통과했다면 등급이 ADULT 로 덮였을 것이다
        assertThat(projectRepository.findByProductCode(productCode).orElseThrow().getRatingCode())
                .isEqualTo("ALL");
        assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "studio")).isTrue();
    }

    @Test
    @DisplayName("반려 이벤트를 반영한다")
    void applyRejection() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);
        gameProjectService.submitForReview(created.getId(), SELLER);

        gameProjectService.applyRejection(UUID.randomUUID().toString(),
                EventType.REVIEW_REJECTED, productCode, "자료 미비");

        GameProject updated = projectRepository.findByProductCode(productCode).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ProjectStatus.REJECTED);
        assertThat(updated.getRejectReason()).isEqualTo("자료 미비");
    }

    @Test
    @DisplayName("[D-017] 승인된 프로젝트에 지각 반려가 와도 컨슈머가 멈추지 않고 상태도 그대로다")
    void lateRejectionIsIgnoredWithoutThrowing() {
        String productCode = uniqueProductCode();
        GameProject created = project(productCode);
        gameProjectService.submitForReview(created.getId(), SELLER);
        gameProjectService.applyApproval(UUID.randomUUID().toString(),
                EventType.REVIEW_APPROVED, productCode, "ALL");

        gameProjectService.applyRejection(UUID.randomUUID().toString(),
                EventType.REVIEW_REJECTED, productCode, "자료 미비");

        assertThat(statusOf(productCode)).isEqualTo(ProjectStatus.APPROVED);
    }

    @Test
    @DisplayName("모르는 상품코드의 심의 결과는 NOT_FOUND 로 드러난다")
    void approvalForUnknownProductCode() {
        // 이 경로는 예외가 리스너 밖으로 나가 재시도된다. 프로젝트가 뒤늦게 보일 수 있는
        // 상황(복제 지연)이라 재시도가 의미를 갖는다 — D-016/D-018 과 판단이 갈리는 지점이다.
        assertThatThrownBy(() -> gameProjectService.applyApproval(UUID.randomUUID().toString(),
                EventType.REVIEW_APPROVED, uniqueProductCode(), "ALL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("내 프로젝트 목록은 최신순이다")
    void findBySellerIsNewestFirst() {
        Long seller = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000) + 500_000;
        GameProject first = gameProjectService.create(
                new NewProject(uniqueProductCode(), "게임 1", seller, 1_000L, "KRW", false));
        GameProject second = gameProjectService.create(
                new NewProject(uniqueProductCode(), "게임 2", seller, 2_000L, "KRW", false));

        assertThat(gameProjectService.findBySeller(seller))
                .extracting(GameProject::getId)
                .containsExactly(second.getId(), first.getId());
    }
}
