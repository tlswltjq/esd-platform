package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.REVIEWER_EMAIL;
import static com.stove.e2e.ReviewJourney.REVIEWER_PASSWORD;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.project;
import static com.stove.e2e.ReviewJourney.request;
import static com.stove.e2e.ReviewJourney.reviewPath;
import static com.stove.e2e.ReviewJourney.waitForResult;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.e2e.E2eClient.Response;
import com.stove.e2e.ReviewJourney.Account;
import com.stove.e2e.ReviewJourney.Project;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Review가 판정을 저장했지만 결과를 아직 내보내지 못한 채 내려갔다가 다시 떠도, 판정은 그대로이고 결과가 Studio에 닿는다.
 *
 * <p>테스트가 Review 프로세스를 직접 재시작한다(결정 e2e-stack-in-junit, 시범 2). 예전에는 파이썬 실행기가 재시작
 * 사이에 테스트 메서드를 하나씩 따로 불렀고, 앞 단계의 값을 증거 파일로 넘겼다.
 */
@Tag("review-restart")
@ExtendWith(LocalStackExtension.class)
@LocalStackApps({StackApp.AUTH, StackApp.STUDIO, StackApp.WEB_BFF, StackApp.GATEWAY, StackApp.REVIEW})
class ReviewRestartFlowTest {

    @Test
    void aDecisionSavedBeforeARestartIsKeptAndPublishedAfterIt(LocalStack stack) throws Exception {
        // 릴레이를 끈 Review: 판정은 저장되지만 결과 이벤트는 Outbox에 남는다.
        stack.restart(StackApp.REVIEW, "--stove.outbox.relay-enabled=false");
        Account creator = account(true);
        Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        Project project = project(creator);
        long id = request(project.id(), creator, reviewer);
        Response approved = Stove.gateway.post(reviewPath(id) + "/approve", null, reviewer.headers());
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        String decidedAt = approved.data().path("decidedAt").asText();
        assertThat(data(project.id(), creator).path("currentReviewRequest").path("status").asText())
                .as("결과가 아직 Studio에 닿지 않았다").isEqualTo("REQUESTED");

        stack.restart(StackApp.REVIEW);

        Response review = Stove.gateway.get(reviewPath(id), reviewer.headers());
        assertThat(review.data().path("status").asText()).isEqualTo("APPROVED");
        assertThat(review.data().path("actor").asText()).isEqualTo(reviewer.subject());
        assertThat(review.data().path("decidedAt").asText()).isEqualTo(decidedAt);
        waitForResult(project.id(), id, creator, "APPROVED");
        assertThat(Stove.gateway.post(reviewPath(id) + "/approve", null, reviewer.headers())
                .data().path("decidedAt").asText()).as("다시 승인해도 처음 판정 그대로다").isEqualTo(decidedAt);
        assertThat(data(project.id(), creator).path("releaseEligible").asBoolean()).isTrue();
    }
}
