package com.stove.common.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 규칙이 <b>실제로 위반을 잡는가</b>.
 *
 * <p>모든 규칙에 {@code allowEmptyShould(true)} 가 걸려 있다. 클래스가 하나뿐인 모듈
 * (gateway)에서도 같은 규칙 세트를 쓰기 위한 선택이지만, 대가가 있다 —
 * <b>{@code that()} 술어가 아무것도 매칭하지 못하면 규칙이 조용히 통과한다.</b>
 * 패키지 이름을 한 번 바꾸거나 애노테이션 상수의 FQCN 에 오타가 나면, 규칙은 남아 있는데
 * 아무것도 검사하지 않는 상태가 되고 그 사실이 초록으로 보인다.
 *
 * <p>이 저장소는 그 규칙들로 위반 166건을 잡아낸 이력이 있다. 그 도구가 침묵으로
 * 무너지는 것을 막으려면, 규칙 자신을 대상으로 한 테스트가 필요하다.
 *
 * <p>방법은 두 갈래다.
 * <ol>
 *   <li><b>위반 픽스처</b>에 대해 규칙이 실패하는가 — 술어가 매칭하고 조건이 거부하는가</li>
 *   <li><b>준수 픽스처</b>에 대해 규칙이 통과하는가 — 아무거나 잡는 규칙은 아닌가</li>
 * </ol>
 */
class ArchRuleEnforcementTest {

    private static final JavaClasses VIOLATING =
            new ClassFileImporter().importPackages("com.stove.archfixture.violating");

    private static final JavaClasses COMPLIANT =
            new ClassFileImporter().importPackages("com.stove.archfixture.compliant");

    /** 규칙이 위반 픽스처를 잡아내는지. 통과해 버리면 그 규칙은 아무 일도 하지 않고 있다. */
    private static void assertCatchesViolation(ArchRule rule, String ruleName) {
        assertThatThrownBy(() -> rule.check(VIOLATING))
                .as("%s 이(가) 위반을 잡지 못했다 — 규칙이 공허하게 통과하고 있다", ruleName)
                .isInstanceOf(AssertionError.class);
    }

    private static void assertAcceptsCompliant(ArchRule rule, String ruleName) {
        assertThatCode(() -> rule.check(COMPLIANT))
                .as("%s 이(가) 규칙을 지킨 코드를 거부했다", ruleName)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("픽스처 자체가 비어 있지 않다 — 이 테스트가 공허해지는 것부터 막는다")
    void fixturesAreNotEmpty() {
        // 픽스처 패키지가 비면 위 단언들이 전부 무의미해진다. 재귀적으로 같은 함정이다.
        assertThat(VIOLATING).isNotEmpty();
        assertThat(COMPLIANT).isNotEmpty();
    }

    @Nested
    @DisplayName("네이밍 규칙")
    class NamingRules {

        @Test
        @DisplayName("core.service 의 이름 규칙은 위반을 잡는다")
        void coreServiceNaming() {
            assertCatchesViolation(ModuleHygieneRules.core_서비스_네이밍, "core_서비스_네이밍");
            assertAcceptsCompliant(ModuleHygieneRules.core_서비스_네이밍, "core_서비스_네이밍");
        }

        @Test
        @DisplayName("컨트롤러 이름 규칙은 위반을 잡는다")
        void controllerNaming() {
            assertCatchesViolation(ModuleHygieneRules.컨트롤러_네이밍, "컨트롤러_네이밍");
            assertAcceptsCompliant(ModuleHygieneRules.컨트롤러_네이밍, "컨트롤러_네이밍");
        }

        @Test
        @DisplayName("리스너 이름 규칙은 위반을 잡는다")
        void listenerNaming() {
            assertCatchesViolation(ModuleHygieneRules.리스너_네이밍, "리스너_네이밍");
            assertAcceptsCompliant(ModuleHygieneRules.리스너_네이밍, "리스너_네이밍");
        }

        @Test
        @DisplayName("설정값 이름 규칙은 위반을 잡는다")
        void propertiesNaming() {
            assertCatchesViolation(ModuleHygieneRules.설정값_네이밍, "설정값_네이밍");
        }
    }

    @Nested
    @DisplayName("경계 규칙")
    class BoundaryRules {

        @Test
        @DisplayName("트랜잭션 경계 규칙(메서드)은 어댑터의 트랜잭션을 잡는다")
        void methodLevelTransactionBoundary() {
            assertCatchesViolation(
                    ModuleHygieneRules.트랜잭션_경계는_core_service_다, "트랜잭션_경계는_core_service_다");
            assertAcceptsCompliant(
                    ModuleHygieneRules.트랜잭션_경계는_core_service_다, "트랜잭션_경계는_core_service_다");
        }

        @Test
        @DisplayName("트랜잭션 경계 규칙(클래스)도 어댑터의 트랜잭션을 잡는다")
        void classLevelTransactionBoundary() {
            assertCatchesViolation(
                    ModuleHygieneRules.클래스_레벨_트랜잭션도_core_service_다,
                    "클래스_레벨_트랜잭션도_core_service_다");
        }

        @Test
        @DisplayName("스텁 격리 규칙은 조건 없는 스텁 빈을 잡는다")
        void unguardedStub() {
            assertCatchesViolation(ModuleHygieneRules.스텁_어댑터는_격리한다, "스텁_어댑터는_격리한다");
        }

        /**
         * [D-021] 접두사 세 개를 <b>따로</b> 확인한다.
         *
         * <p>한꺼번에 검사하면 셋 중 하나만 잡혀도 규칙이 실패하므로 통과한다 —
         * 실제로 그랬다. {@code Fake} 하나만 살아 있고 {@code Mock}·{@code Stub} 은
         * 죽어 있었는데, 리포의 스텁 4개는 전부 {@code Mock} 이었다.
         */
        @ParameterizedTest(name = "{0} 접두사 스텁도 잡는다")
        @ValueSource(strings = {"MockUnguardedClient", "StubUnguardedClient", "FakeUnguardedClient"})
        void everyStubPrefixIsChecked(String simpleName) {
            JavaClasses onlyThisOne = new ClassFileImporter()
                    .withImportOption(location -> location.contains(simpleName + ".class"))
                    .importPackages("com.stove.archfixture.violating");

            assertThat(onlyThisOne).as("픽스처 %s 를 못 찾았다", simpleName).isNotEmpty();
            assertThatThrownBy(() -> ModuleHygieneRules.스텁_어댑터는_격리한다.check(onlyThisOne))
                    .as("%s 는 규칙의 검사 대상에서 빠져 있다", simpleName)
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Nested
    @DisplayName("일반 위생 규칙")
    class HygieneRules {

        @Test
        @DisplayName("설정값 불변 규칙은 setter 달린 설정을 잡는다")
        void mutableConfiguration() {
            assertCatchesViolation(ModuleHygieneRules.설정값은_불변이다, "설정값은_불변이다");
        }

        @Test
        @DisplayName("필드 주입 금지는 @Autowired 필드를 잡는다")
        void fieldInjection() {
            assertCatchesViolation(ModuleHygieneRules.필드_주입_금지, "필드_주입_금지");
            assertAcceptsCompliant(ModuleHygieneRules.필드_주입_금지, "필드_주입_금지");
        }
    }

    /**
     * 컨슈머 그룹 규칙.
     *
     * <p>이 규칙은 {@code ArchRule} 이 아니라 {@code JavaClasses} 를 받는 메서드다. 앱 하나의
     * 클래스 <b>전체</b>를 모아 집합끼리 비교해야 하는데 {@code ArchRule} 은 클래스 단위로 돌기 때문이다.
     * 그래서 위 헬퍼를 쓰지 못하고 픽스처를 앱 단위로 하나씩 만든다.
     */
    @Nested
    @DisplayName("컨슈머 그룹 규칙")
    class ConsumerGroup {

        /** 앱 하나를 모사한 픽스처 패키지. 비어 있으면 그 아래 단언이 전부 무의미해진다. */
        private JavaClasses app(String name) {
            JavaClasses classes = new ClassFileImporter()
                    .importPackages("com.stove.archfixture.consumergroup." + name);
            assertThat(classes).as("픽스처 %s 가 비어 있다", name).isNotEmpty();
            return classes;
        }

        @Test
        @DisplayName("Kafka 그룹과 멱등 키가 갈라지면 잡는다")
        void divergentGroupIsCaught() {
            assertThatThrownBy(() -> ConsumerGroupRules.컨슈머_그룹과_멱등키가_같다(app("divergent")))
                    .as("갈라진 그룹을 잡지 못했다 — 규칙이 공허하게 통과하고 있다")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("kafka-side")
                    .hasMessageContaining("inbox-side");
        }

        @Test
        @DisplayName("한 상수에서 나온 그룹은 통과시킨다")
        void alignedGroupIsAccepted() {
            assertThatCode(() -> ConsumerGroupRules.컨슈머_그룹과_멱등키가_같다(app("aligned")))
                    .as("규칙을 지킨 앱을 거부했다")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Inbox 가드를 쓰지 않는 앱은 건너뛴다 — store·download")
        void guardlessAppIsSkipped() {
            assertThatCode(() -> ConsumerGroupRules.컨슈머_그룹과_멱등키가_같다(app("guardless")))
                    .as("가드가 없어 대조할 것이 없는 앱을 거부했다")
                    .doesNotThrowAnyException();
        }

        /**
         * 제외 조건을 상수의 부재로 판정하면 이 앱이 조용히 통과한다.
         *
         * <p>가드를 쓰는데 멱등 키가 상수 밖으로 나간 상태다. {@code guardless} 와 겉모습이 같지만
         * ({@code CONSUMER_GROUP} 이 없다) 성격은 정반대다 — 검사할 것이 없는 게 아니라
         * <b>검사 대상이 사라진 것</b>이다. 둘을 가르는 것이 {@code ProcessedEventGuard} 주입 여부다.
         *
         * <p>이 테스트가 D-021 과 같은 부류의 공허 통과를 막는다.
         */
        @Test
        @DisplayName("가드는 쓰는데 멱등 키 상수가 사라지면 잡는다")
        void guardWithoutConstantIsCaught() {
            assertThatThrownBy(() -> ConsumerGroupRules.컨슈머_그룹과_멱등키가_같다(app("missing")))
                    .as("상수가 사라진 앱이 '가드를 안 쓰는 앱' 과 같은 문으로 빠져나갔다")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("CONSUMER_GROUP 상수가 없다");
        }

        @Test
        @DisplayName("groupId 를 빠뜨린 리스너를 잡는다")
        void missingGroupIdIsCaught() {
            assertThatThrownBy(() -> ConsumerGroupRules.리스너는_컨슈머_그룹을_명시한다(app("nogroup")))
                    .as("groupId 없는 리스너를 잡지 못했다 — yml 기본값을 지웠으므로 이 리스너는 그룹이 없다")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("NoGroupListener");
        }

        @Test
        @DisplayName("groupId 를 명시한 리스너는 통과시킨다")
        void declaredGroupIdIsAccepted() {
            assertThatCode(() -> ConsumerGroupRules.리스너는_컨슈머_그룹을_명시한다(app("aligned")))
                    .as("groupId 를 명시한 리스너를 거부했다")
                    .doesNotThrowAnyException();
        }
    }
}
