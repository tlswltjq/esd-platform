# 미결 질문과 검토 기록

[용어집](../domain-glossary.md) · [업무 흐름](workflows.md) · [창작·출시](publishing.md) · [구매·정산](commerce.md)

2026-10-02, 코드 `a525243` (`main`)에서 확인한 내용이다. **관찰한 구현**, **결정이 필요한 업무 의도**, **후속 작업**을 구분한다. 아래 Q 번호가 이번 문서 작업의 후속 관리 단위이며, 외부 이슈로 등록한 것으로 표시하지 않는다. 결정 후 담당자·날짜·근거와 구현 이슈/PR을 연결하고 질문을 해결 상태로 보존한다.

## 검토 순서

| 질문 | 검토 대상 | 상태 | 필요한 검토 주체 |
|---|---|---|---|
| [Q1](#q1) | 표준명·동사·업무 경계 후보 | 초안 검토 대기 | 프로젝트 담당자 |
| [Q2](#q2) | 프로젝트 심사와 제출물 심사의 공존 | 결정 필요 | 창작·심사 담당 |
| [Q3](#q3) | 공개 시 자동 판매와 판매 중지 유지 | 결정 필요 | 출시·판매 운영 담당 |
| [Q4](#q4) | 심사 운영 상태·재검토·승인 조건 | 결정 필요 | 심사·출시 담당 |
| [Q5](#q5) | 상품 종류·수량·반복 구매와 권한 사본 | 결정 필요 | 구매·이용 권한 담당 |
| [Q6](#q6) | 귀속 월·마감 이후 변경·이월·송금 | 결정 필요 | 정산 담당 |
| [Q7](#q7) | 이력 ID 명명과 가격의 출처 | 결정 필요 | 창작·상품 담당 |
| [Q8](#q8) | 워크스페이스·판매자·회원 식별 | 결정 필요 | 계정·창작·정산 담당 |
| [Q9](#q9) | 주문 취소·환불·통화의 연결 | 구현 차이 검토 필요 | 주문·결제 담당 |
| [Q10](#q10) | 플랫폼 검증·smoke test·롤백 성공 의미 | 구현 차이 검토 필요 | 빌드·출시 담당 |
| [Q11](#q11) | 규칙 근거의 테스트 공백 | 후속 검증 필요 | 개발·테스트 담당 |

역할은 필요한 책임 범위를 나타낸다. 실제 담당자 배정이나 검토가 완료되었다는 뜻은 아니다.

<a id="q1"></a>
## Q1. 어떤 표준명과 경계에 합의할 것인가?

**현상:** 기존 문서·코드에는 “심의/심사”, “발행/공개”, “소유권/라이선스/권한”이 섞여 있다. 기존 용어 위키의 식별자·리비전·빌드·읽기 모델 설명은 용어집과 상세 정의에 통합했으며, 표준명과 업무 경계의 담당자 합의는 아직 필요하다.

**제안과 질문:** [15개 용어](../domain-glossary.md#핵심-용어-색인)를 표준명으로 사용할지 검토한다. 특히 “상점 자료 확정”, “채널별 릴리스 공개”, “판매 시작”, “심사 사건”, “출시 관문”, “이용권”, “다운로드 보유권 사본”이 실제 업무 대화에서 명확한가? [업무 경계 후보](../domain-glossary.md#의미가-유지되는-업무-범위)가 의미를 책임지는 범위를 올바르게 나누는가?

**후속:** 담당자가 용어·정상/거절 사례를 검토하고 아래 [검토 기록](#review-log)에 수용/수정/보류 결과를 남긴다. 합의 전에는 코드 명칭 변경을 확정하지 않는다.

<a id="q2"></a>
## Q2. 기존 프로젝트 단위 심사 경로의 역할은 무엇인가?

**관찰:** `GameProject.submit()`·`GameRegistered`·`ReviewRequest`·`ReviewApproved` 경로가 남아 있고, 새 경로는 `SubmissionCreated`·`ReviewCase`·`SubmissionGate`·`ReleasePublished`를 사용한다. 기존 승인으로 생성한 Product는 공개 릴리스가 없으면 판매할 수 없다. 새 Submission의 준비 상태가 프로젝트의 기존 `status`에 그대로 반영되지는 않는다.

**결정할 내용:** 기존 경로를 호환 전용으로 계속 제공할지, 사용 중단 후 제거할지 정해야 한다. 화면에서 “프로젝트 승인”을 표시할 때 어떤 제출물·릴리스의 상태를 뜻하는지도 필요하다.

**후속:** 사용처를 확인하고 API·화면의 상태 표현 및 이전 계획을 별도 작업으로 정한다. 근거: [GameProjectService](../../apps/studio/src/main/java/com/stove/studio/core/service/GameProjectService.java), [SubmissionService](../../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionService.java), [ProductTest](../../apps/catalog/src/test/java/com/stove/catalog/core/domain/ProductTest.java). 현재 대표 업무는 [새 제출 흐름](workflows.md#publishing-flow)으로 기술했다.

<a id="q3"></a>
## Q3. 공개하면 자동으로 판매를 시작해야 하는가?

**관찰:** `Product.applyRelease()`가 일반 상품을 `ON_SALE`로 바꾼다. 기존 `SUSPENDED`도 새 LIVE 릴리스 반영 시 다시 판매된다. DEMO·BUNDLE은 `applyFamily()`에서 `APPROVED`로 바뀌고 운영 판매 시작도 거절된다. 판매 중지는 기존 이용권을 회수하지 않는다. `CLOSED`는 enum에 있으나 Product에서 그 상태로 전환하는 경로는 없다.

**결정할 내용:** 릴리스 공개와 판매 시작을 항상 묶을지, 운영 판매 중지 사유를 새 공개 후에도 유지할지 정해야 한다. 판매 종료(`CLOSED`)가 필요하다면 재개 가능 여부와 기존 구매자의 다운로드 정책도 정의한다.

**후속:** 판매 운영 정책을 합의한 뒤 상태 전이·테스트·운영 API를 별도 변경한다. 근거: [Product](../../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java), [ProductCommandService](../../apps/catalog/src/main/java/com/stove/catalog/core/service/ProductCommandService.java), [DownloadTicketService](../../apps/download/src/main/java/com/stove/download/core/service/DownloadTicketService.java). 정의: [상품 판매 상태](publishing.md#product-status).

<a id="q4"></a>
## Q4. 심사 사건의 운영 상태와 출시 관문은 어떻게 연결되는가?

**관찰:** ReviewCase에는 `BLOCKED/CANCELLED/EXPIRED`가 있으나 SubmissionGate에는 `PENDING/APPROVED/CHANGES_REQUESTED`만 있다. 승인·수정 요청·이의 제기 이벤트는 Studio로 전달되지만 차단·취소·만료는 현재 해당 이벤트가 없다. 승인 코드에는 배정자 일치와 체크리스트 완료 검사가 없다. 이의 제기는 같은 스냅샷의 회차를 늘리며, 기존 외부 접수 증빙도 자동 제거하지 않는다.

**결정할 내용:** Studio가 단순 대기와 차단·만료·취소를 구별해야 하는가? 재검토에서 어느 관문과 외부 증빙을 유지할 것인가? 승인에 담당자·체크리스트 조건이 필요한가? 자료 변경을 새 제출로 처리하는 제안에 합의할 수 있는가?

**후속:** 사건·관문 전이 표와 재검토 규칙을 결정하고 필요한 이벤트·테스트를 추가하는 별도 작업으로 연결한다. 근거: [ReviewCase](../../apps/review/src/main/java/com/stove/review/core/domain/ReviewCase.java), [SubmissionReviewService](../../apps/review/src/main/java/com/stove/review/core/service/SubmissionReviewService.java), [SubmissionReviewProjectionService](../../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionReviewProjectionService.java). 정의: [심사 사건](publishing.md#review-case), [출시 관문](publishing.md#submission-gate).

<a id="q5"></a>
## Q5. 상품 한 개를 산다는 것은 어떤 이용 권한을 얻는가?

**관찰:** DEMO 무료 청구와 BUNDLE 구성품 지급은 미지원이라 일반 판매가 막혀 있다([P2 명세](../p2-product-family-and-builds.md)). 주문 수량은 1 이상을 허용하고 같은 상품 항목의 반복도 견적에서 합산한다. License는 주문·상품별 한 건, Settlement는 주문·상품·기록 종류별 한 건을 저장한다. 정산은 가격·할인·수수료 사본이 같은 반복 항목의 수량·금액을 합산한다. Download는 회원·상품별 하나의 활성 문서와 한 주문번호를 보관한다. 회수 시 주문번호를 비교하지만 지급은 문서를 덮어쓴다.

**결정할 내용:** 수량은 좌석 수인가, 개인 보유형 상품에서는 1로 제한할 것인가? 이미 보유한 상품의 재구매·선물·여러 주문의 활성 이용권은 어떻게 취급하는가? DEMO는 무료 이용권이 필요한가? BUNDLE·EDITION·DLC가 부모·구성품에 어떤 권한을 주며 환불 시 무엇을 회수하는가? 늦은 지급 이벤트를 포함한 순서 역전에서 사본의 권한을 어떻게 보존하는가?

**후속:** 종류·수량·주문 항목 정규화·권한 지급/회수 표를 합의하고 구매·정산·사본 모델 변경을 별도 작업으로 정한다. 늦은 회수 방어 테스트만으로 모든 순서와 다중 주문을 보장한다고 설명하지 않는다. 근거: [ProductQueryService](../../apps/catalog/src/main/java/com/stove/catalog/core/service/ProductQueryService.java), [LicenseIssueEventTest](../../apps/license/src/integrationTest/java/com/stove/license/core/service/LicenseIssueEventTest.java)의 `duplicateProductLinesIssueSingleLicense`, [SettlementRecordService](../../apps/settlement/src/main/java/com/stove/settlement/core/service/SettlementRecordService.java), [EntitlementService](../../apps/download/src/main/java/com/stove/download/core/service/EntitlementService.java), [DownloadEntitlementTest](../../apps/download/src/integrationTest/java/com/stove/download/core/service/DownloadEntitlementTest.java).

<a id="q6"></a>
## Q6. 어느 달에 귀속하고 마감 후에는 무엇을 바꿀 수 있는가?

**관찰:** 매출·환불 귀속 월은 이벤트를 처리하는 서버의 현재 날짜로 정한다. 결제일·환불일을 사용해 원래 발생 월로 되돌리지 않는다. 주문의 할인 부담·정산 기준·수수료 사본이 현재 정산 설정보다 우선한다. 최초 마감 금액은 `base*`에 보존하고 같은 월의 지각 원장은 재마감 시 `adjustment*`와 합계에 누적하며 `closedAt`은 최초 시각을 유지한다. 마감 매출의 환불은 `adjustmentForMonth`로 원래 월을 연결하고 대사 API가 조정·차이를 반환한다. 현재 계산서 발행은 MockTaxInvoiceIssuer의 `SIMULATED` 결과이며 기존 번호가 있는 금액의 변경은 경고 로그만 남긴다. 0 이하 정산액의 계산서 미발행은 구현되어 있으나 다음 달 이월 원장·실제 판매자 송금은 구현 근거가 없다. 환불 모델은 주문 상품별 전액 상계다.

**결정할 내용:** 귀속 시각은 결제 발생일인가 수신일인가, 시간대는 무엇인가? 구현된 조정 원장·최초 마감액 보존을 업무 정책으로 수용할 것인가? 부분 환불·실제 수정계산서·이월은 어떻게 기록할 것인가? 월 마감과 송금 완료는 어떤 별도 단계인가?

**후속:** 월 경계 지연과 마감 후 환불의 예시를 합의하고 미지원 정책 및 어댑터 구현을 별도 작업으로 만든다. 근거: [SettlementRecordService](../../apps/settlement/src/main/java/com/stove/settlement/core/service/SettlementRecordService.java), [SellerSettlementService](../../apps/settlement/src/main/java/com/stove/settlement/core/service/SellerSettlementService.java), [SettlementCloseFacade](../../apps/settlement/src/main/java/com/stove/settlement/api/application/SettlementCloseFacade.java), [SettlementCloseTest](../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/SettlementCloseTest.java), [PromotionReconciliationTest](../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/PromotionReconciliationTest.java). 정의: [원장](commerce.md#settlement-record), [월 마감](commerce.md#seller-settlement).

<a id="q7"></a>
## Q7. 자료 이력과 가격의 출처를 어떻게 명명할 것인가?

**관찰:** `StorePageRevision.revisionNo`는 순번인데 이벤트·Review 스냅샷·Catalog의 `metadataRevision`에는 이력 ID가 전달된다. `StorePageRevision.pricesJson`과 별도 `PricingRevision`이 공존하며 현재 공개 가격은 후자에서 가져온다. 현재 PricingRevision 생성은 KR/KRW다.

**결정할 내용:** 이벤트 필드의 ID 의미를 명칭에 반영할지, 자료에 포함된 통화별 가격과 주문 가격의 책임을 어떻게 나눌지 정해야 한다.

**후속:** 문서에서는 [ID와 순번](../domain-glossary.md#versions)을 명시했다. 필드 변경은 이벤트 호환성을 검토한 별도 작업으로 처리하고 가격 편집·심사·공개 규칙을 연결한다. 근거: [RevisionService](../../apps/studio/src/main/java/com/stove/studio/core/service/RevisionService.java), [SubmissionService](../../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionService.java), [ReleaseService](../../apps/studio/src/main/java/com/stove/studio/core/service/ReleaseService.java).

<a id="q8"></a>
## Q8. 판매자·워크스페이스·회원은 어떤 관계인가?

**관찰:** GameProject의 `sellerId`는 `workspace_id`에 저장되고 커머스 이벤트의 판매자로 전달된다. 회원은 Auth의 `memberId`다. Catalog는 자체 판매자 ID와 상품 판매자 ID로 수수료율을 결정해 주문 항목에 고정한다. 정산은 그 사본을 우선 사용하며, 사본이 없는 이전 이벤트에는 설정된 자체 판매자 ID·수수료율을 적용한다.

**결정할 내용:** 개인 워크스페이스가 계속 판매 주체와 일치하는가? 조직·공동 창작·판매 계약·정산 계좌를 도입하면 판매자 식별자를 별도로 두어야 하는가? 자체 판매자 ID는 어떤 업무 주체를 뜻하는가?

**후속:** 현재 식별자 매핑을 유지해 설명하되 판매자 모델 변경은 계정·정산 정책과 함께 결정한다. 근거: [GameProject](../../apps/studio/src/main/java/com/stove/studio/core/domain/GameProject.java), [Workspace](../../apps/studio/src/main/java/com/stove/studio/core/domain/Workspace.java), [FeePolicy](../../apps/settlement/src/main/java/com/stove/settlement/core/domain/FeePolicy.java). 표준 표기: [식별자](../domain-glossary.md#identifiers).

<a id="q9"></a>
## Q9. 주문 취소가 환불을 보장하는가? 통화는 끝까지 전달되는가?

**관찰:** `OrderCommandService.cancelOrder()`는 `Order.cancel()`을 호출하며 `PAID`에서도 취소된다. `OrderCanceled`를 발행하지만 Payment의 주문 리스너는 `OrderCreated`만 처리한다. 따라서 주문 취소만으로 PG 환불이 실행된다고 설명할 수 없다. 결제 서비스의 환불 경로는 별도로 존재한다. 또한 OrderCreated의 수신 처리에서 결제 통화를 KRW로 지정한다.

**제안과 질문:** 결제 전 주문 종료와 결제 후 환불을 구별하되, 결제된 주문 취소를 거절할지 환불 요청으로 연결할지 결정해야 한다. 결제 중인 주문과 취소의 경합도 포함한다. 다중 통화를 지원할 계획이면 현재 이벤트·정산의 KRW 전제를 어떻게 바꿀 것인가?

**후속:** 취소와 환불 연결을 우선 확인할 별도 코드 수정 대상으로 남긴다. 이 문서의 환불 시나리오는 Payment의 환불 API를 사용한다. 실제 PG 계약은 기존 [#82](https://github.com/tlswltjq/esd-platform/issues/82)와 함께 검토한다. 근거: [OrderCommandService](../../apps/order/src/main/java/com/stove/order/core/service/OrderCommandService.java), [Order](../../apps/order/src/main/java/com/stove/order/core/domain/Order.java), [OrderEventListener](../../apps/payment/src/main/java/com/stove/payment/api/listener/OrderEventListener.java), [RefundFacade](../../apps/payment/src/main/java/com/stove/payment/api/application/RefundFacade.java).

<a id="q10"></a>
## Q10. 빌드 검증·공개 직전 검사·롤백 완료가 보장하는 것은 무엇인가?

**관찰:** 제출·다운로드 모델은 Windows/Mac/Linux 변형을 다루지만 `BuildValidationService`의 ZIP 검사에는 `.exe` 파일 존재 조건이 있다. `ReleaseSmokeTestService`는 빌드 상태와 저장 객체 크기를 확인하며 실제 실행이나 델타 적용을 검사하지 않는다. `ReleaseService.publish()`는 smoke 실패 시 실패 상태를 기록하고 반환한다. `rollback()`은 그 반환 후에도 `ReleaseRolledBack`과 성공처럼 읽히는 감사 기록을 적재한다.

**결정할 내용:** 플랫폼별 유효 산출물과 델타 검증 기준은 무엇인가? “smoke test 통과”를 파일 접근 점검으로 명명할지 실행 검사를 추가할지 정한다. 롤백 완료 사실은 공개 성공 후에만 발생해야 하는지 확인하고 실패·요청·완료를 구별한다.

**후속:** 플랫폼 검증 조건, 공개 직전 검사 범위, 실패한 롤백의 이벤트 발행을 별도 수정·검증 대상으로 정한다. 근거: [BuildValidationService](../../apps/studio/src/main/java/com/stove/studio/core/service/BuildValidationService.java), [ReleaseSmokeTestService](../../apps/studio/src/main/java/com/stove/studio/core/service/ReleaseSmokeTestService.java), [ReleaseService](../../apps/studio/src/main/java/com/stove/studio/core/service/ReleaseService.java). [SubmissionBuildSetTest](../../apps/studio/src/test/java/com/stove/studio/core/service/SubmissionBuildSetTest.java)는 검증 완료 상태의 객체로 묶음 규칙을 확인하므로 실제 Mac/Linux 파일 검증을 보장하지 않는다.

<a id="q11"></a>
## Q11. 어느 규칙은 테스트 근거가 부족한가?

**확인 범위:** 자료 확정 불변성, 제출 빌드 집합, 심사 등급 증빙, 엔티티 수준 공개 조건, 상품 판매 조건, 주문·결제 검증, 이용권 중복·회수, 사본의 늦은 회수 방어, 정산 상계·재마감은 각 정의의 기존 테스트로 대조했다. 이번 문서 변경에서 애플리케이션 테스트를 새로 실행한 결과로 표시하지 않는다.

**최신 근거:** [TrackACreatorFlowTest](../../e2e/src/test/java/com/stove/e2e/TrackACreatorFlowTest.java)는 업로드 이미지 누락·가짜 URL 거절, 검증 실패 빌드·미승인 출시 거절, 수정 요청·재제출·전체 승인, LIVE의 Catalog·Store·Download 반영, 정상 롤백, 채널 승격·예약 변경·취소를 검증한다. [PromotionReconciliationTest](../../apps/settlement/src/integrationTest/java/com/stove/settlement/core/service/PromotionReconciliationTest.java)는 주문 사본 우선·반복 항목 합산·마감 후 환불 조정을 검증한다.

**공백:** 현재 조사 범위(`apps/*/src/test`, `apps/*/src/integrationTest`, `e2e/src/test`)에서 잘못된 승인 스냅샷 대조와 smoke 실패 시 롤백 이벤트·감사 기록을 직접 검증하는 테스트를 찾지 못했다. [SubmissionTest](../../apps/studio/src/test/java/com/stove/studio/core/domain/SubmissionTest.java)는 상태 전이만, [ReleaseTest](../../apps/studio/src/test/java/com/stove/studio/core/domain/ReleaseTest.java)는 엔티티 수준 smoke 통과 전 공개 차단을 검증한다. 정상 E2E 통과를 모든 순서 역전·실패 경로의 보장으로 확대하지 않는다.

**후속:** Q4·Q10의 의도를 결정한 뒤 해당 정상·거절·지연 시나리오의 테스트를 별도 구현 작업에 포함한다. 문서 근거 표시는 코드 관찰과 테스트 검증 범위를 계속 구별한다.

<a id="review-log"></a>
## 검토 기록과 이슈 완료 조건

| 날짜 | 검토자 | 대상 | 결과·합의 여부 |
|---|---|---|---|
| 2026-10-01 | Codex, 구현 근거 조사·초안 작성 | 기존 문서, 15개 핵심 용어의 코드·테스트, 문서 간 연결 | 현재 동작과 규칙 제안을 구별하고 Q1~Q11 작성. 업무 합의로 처리하지 않음 |
| 2026-10-02 | Codex, 최신 main 근거 대조 | 코드 `a525243`, 기존 용어 위키, 할인·정산 사본, 이미지 업로드·창작자 E2E | 기존 용어 설명을 통합하고 구현·테스트 범위를 갱신. 업무 합의나 애플리케이션 테스트 재실행으로 처리하지 않음 |
| 미정 | 프로젝트 담당자, 검토 대기 | 표준 용어·사례·업무 경계·미결 질문 | 담당자 검토 결과 미수신. 합의 여부를 초안/재검토 필요로 유지 |

담당자는 검토한 용어·질문 번호별로 **수용 / 수정 후 수용 / 보류**, 검토자, 날짜, 근거를 남긴다. 일부만 합의되면 해당 정의만 상태를 변경하고 나머지는 후속 결정으로 남긴다. 설계 이유가 생기면 [decisions.md](../decisions.md)에 연결한다.

| #89 완료 기준 | 이번 문서의 결과 |
|---|---|
| 기존 초안 검토·시작점·탐색 | main의 기존 용어 위키와 대조·통합. README→용어집→흐름/정의/질문 연결 |
| 대표 흐름과 용어 연결 | [창작·심사·출시](workflows.md#publishing-flow), [구매·권한](workflows.md#purchase-flow), [환불·정산](workflows.md#operations-flow) 작성 |
| 핵심 10~15개 정의 | 15개에 표준명·정의·맥락·관계·행위·규칙·사례·근거·합의 상태 기록 |
| 혼동·행위·상태 조건 | 확정/공개/판매, 버전, 식별자, 원본/사본 비교와 상세 전이 조건 작성 |
| 규칙·구현·미결 질문 구분 | 규칙 제안과 관찰을 구분하고 Q1~Q11에 후속 결정 기록 |
| 코드·기존 테스트 대조·링크 | 최신 main의 정의별 근거 연결, 직접 검증되지 않은 범위는 Q11에 기록. 2026-10-02 변경 Markdown 8개의 로컬 링크·앵커 338개와 15개 정의의 필수 항목 정적 검사 통과 |
| 담당자 검토와 합의 결과 | **검토 대기. 이 조건을 완료로 표시하지 않음** |
| 유지 관리 | [용어집 유지 관리 규칙](../domain-glossary.md#유지-관리) 작성 |

문서 초안 작성과 담당자 용어 합의는 별도 단계다. 담당자 검토 결과가 기록되기 전에는 #89 전체 완료로 간주하지 않는다.
