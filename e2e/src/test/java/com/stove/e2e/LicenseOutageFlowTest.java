package com.stove.e2e;

import static com.stove.e2e.PurchaseJourney.purchase;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.e2e.E2eClient.Response;
import com.stove.e2e.PurchaseJourney.Purchase;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 장애 주입(L7): License를 실제로 멈추고, 장애가 번지지 않는지·Download가 닫힌 채 실패하는지·되살리면 돌아오는지 본다.
 *
 * <p>Download는 보유권을 자기 저장소에 두지 않고 License에 묻는다. License가 응답하지 않으면 미보유로 바꾸지 않고
 * {@code 503}으로 끝낸다(Download R9.3). 그래서 이 장의 판정은 "License 없이도 내려받는다"가 아니라
 * <b>"License 없이는 내려받지 못하고, 그 실패가 미보유나 허용으로 둔갑하지 않는다"</b>다.
 *
 * <p>판정 순서: 주입 전이 깨끗하다 → 주입이 반영됐다(여기가 통과하지 않으면 아래 초록은 "견뎠다"가 아니라 "안
 * 넣었다"다) → 번지지 않았다 → 닫힌 채 실패한다 → 복구된다. 테스트가 스택을 직접 멈추고 되살린다(결정
 * chaos-test-rebuild).
 */
@Tag("chaos")
@ExtendWith(LocalStackExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LicenseOutageFlowTest {

    private static final StackApp VICTIM = StackApp.LICENSE;

    private static Purchase owned;

    @BeforeAll
    static void aBuyerOwnsAPublishedProduct() throws Exception {
        owned = purchase("Outage product");
    }

    @Test
    @Order(1)
    void baselineIsClean(LocalStack stack) {
        assertThat(unhealthy(stack, null)).as("주입 전에 이미 응답하지 않는 앱").isEmpty();
        Response ticket = ticket();
        assertThat(ticket.status()).as("주입 전 보유자의 티켓 — %s", ticket).isEqualTo(200);
    }

    @Test
    @Order(2)
    void outageIsActuallyInjected(LocalStack stack) {
        stack.stop(VICTIM);
        assertThat(health(stack, VICTIM)).as("멈춘 License의 상태(0 = 무응답)").isZero();
    }

    @Test
    @Order(3)
    void outageDoesNotSpread(LocalStack stack) {
        assertThat(unhealthy(stack, VICTIM)).as("License와 함께 응답을 멈춘 앱").isEmpty();
        Response order = Stove.gateway.get("/api/v1/orders/" + owned.orderNo(), owned.buyer().headers());
        assertThat(order.status()).as("License와 무관한 조회는 계속된다 — %s", order).isEqualTo(200);
        assertThat(Stove.gateway.get("/api/v1/storefront/products/" + owned.productCode()).status())
                .as("공개 상품 조회").isEqualTo(200);
    }

    @Test
    @Order(4)
    void downloadFailsClosedWithoutLicense() {
        Response ticket = ticket();
        assertThat(ticket.status())
                .as("보유자라도 License 없이는 URL을 받지 못한다. 200이면 확인 없이 내준 것이고, "
                        + "403이면 장애가 미보유로 둔갑한 것이다 — %s", ticket)
                .isEqualTo(503);
        assertThat(ticket.errorCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(ticket.data().path("downloadUrl").isMissingNode()).as("URL이 실리지 않는다").isTrue();
    }

    @Test
    @Order(5)
    void serviceRecovers(LocalStack stack) {
        stack.start(VICTIM);
        assertThat(health(stack, VICTIM)).isEqualTo(200);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Response ticket = ticket();
            assertThat(ticket.status()).as("복구 뒤 보유자의 티켓 — %s", ticket).isEqualTo(200);
        });
    }

    private static Response ticket() {
        return Stove.gateway.get("/api/v1/downloads/" + owned.productCode() + "/ticket", owned.buyer().headers());
    }

    /** {@code except}를 뺀 앱 가운데 상태 확인에 200으로 답하지 않는 앱과 그 상태. */
    private static Map<String, Integer> unhealthy(LocalStack stack, StackApp except) {
        Map<String, Integer> failing = new LinkedHashMap<>();
        for (StackApp app : stack.apps()) {
            int status = app == except ? 200 : health(stack, app);
            if (status != 200) {
                failing.put(app.module(), status);
            }
        }
        return failing;
    }

    /**
     * 무응답을 0으로 읽는다. 이 장에서 무응답은 오류가 아니라 결과다 — 멈춘 앱은 응답하지 않는 것이 정상이고, 다른 앱이
     * 무응답이면 장애가 번진 것이다. 예외로 두면 그 둘이 같은 모양이 된다.
     */
    private static int health(LocalStack stack, StackApp app) {
        try {
            return new E2eClient(stack.address(app)).get("/actuator/health").status();
        } catch (RuntimeException unreachable) {
            return 0;
        }
    }
}
