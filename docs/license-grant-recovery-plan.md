# P3 결제 후 지급·지급 확인의 기존 이벤트 복구 계획

2026-10-31. 사용자 요청으로 [전체 기능 일반화](platform-generalization-plan.md)의 G2 후속 P3를 검토했다. 조사 시작 시 기준 코드는 `70d2176`이며 작업 트리는 깨끗했다. **사용자는 재제안의 A를 선택했다([결정 37](decisions.md#license-event-recovery)). 기존 복구 경로 검증·운영 절차 완성을 구현·검증 완료했다([결과 기록](license-event-recovery-verification.md)). 실제 거래의 누락을 발견했다는 운영 진단은 아니다.**

사용자의 at-least-once·DLT 관련 질문과 재제안 요청을 반영해 범위를 수정했다. 기존 DLT 재투입·Outbox DEAD 재큐잉 도구를 먼저 검증하고 활용한다. 별도 지급 엔진·확인 이벤트 재생성 기능의 필요성을 전제하지 않는다. **아래 A/B는 앞선 자동 보완 A / 운영자 실행 B를 대체한 새 선택지이며, 사용자는 기존 복구 경로 검증·운영 절차 완성 A를 선택했다. 별도 선택 이유는 덧붙이지 않았다.**

## 해결할 고객 문제

정상 흐름은 **Order 결제 완료 → License 보유권 지급 → Order에 지급 사실 확인**이다. Library·Download는 License를 보고, 환불은 Order에 저장한 지급 확인을 기다린 뒤 실제 정지를 요청한다.

| 사례 | 고객에게 보이는 문제 | 우선 복구할 처리 |
|---|---|---|
| 9,000원 구매가 PAID인데 License 지급이 끝나지 않음 | 결제는 성공했지만 라이브러리·다운로드를 사용할 수 없음 | 남아 있는 원래 OrderPaid의 발행 또는 소비 |
| License는 지급됐으나 Order의 지급 확인이 비어 있음 | 라이브러리는 보이지만, 환불에서 지급 확인을 계속 기다릴 수 있음 | 남아 있는 원래 LicenseGranted의 발행 또는 소비 |

예를 들어 License DB 오류로 OrderPaid 처리가 반복 실패해 DLT로 이동하면, 이벤트가 보존되어 있어도 보유권 지급은 아직 끝나지 않는다. DB가 정상화된 뒤 원래 이벤트를 재투입해 정상 지급 경로를 다시 실행할 수 있다. 이는 코드상 가능한 실패 경로의 예시이며 실제 거래에서 관측한 사고가 아니다.

두 사례를 한 종류의 재지급으로 처리하지 않는다. 이미 지급한 구매는 기존 licenseId·원래 구매 근거·현재 이용권 상태를 보존하고 원래 지급 확인 이벤트의 처리를 이어간다.

## 현재 구현과 공백

- [ApplyPaymentResult](../apps/order/src/main/java/com/stove/order/core/service/ApplyPaymentResult.java)는 PAID 변경과 원래 OrderPaid Outbox를 같은 트랜잭션에 저장한다. 일시적 발행 오류는 기존 릴레이가 재시도한다.
- [GrantLicense](../apps/license/src/main/java/com/stove/license/core/service/GrantLicense.java)는 OrderPaid의 내부 입력을 검증하고 License·Inbox·LicenseGranted Outbox를 함께 저장한다. 같은 주문 재전달은 원구매 전체와 기존 Inbox를 대조하며 기존 보유권을 유지한다. 이미 지급된 주문을 다시 받으면 새 LicenseGranted를 발행하지 않는다.
- [ApplyLicenseGrant](../apps/order/src/main/java/com/stove/order/core/service/ApplyLicenseGrant.java)는 원주문을 대조해 지급 확인·Inbox를 저장하고, 지급 확인을 기다리던 환불의 정지 요청을 이어간다.
- [RequestRefund](../apps/order/src/main/java/com/stove/order/core/service/RequestRefund.java)는 저장된 지급 확인을 사용한다. 현재 License의 실제 지급을 직접 조회해 누락을 보완하는 흐름은 없다.
- [ConsumerRetryPolicy](../common/kafka/src/main/java/com/stove/common/kafka/ConsumerRetryPolicy.java)와 [KafkaConsumerAutoConfiguration](../common/kafka/src/main/java/com/stove/common/kafka/KafkaConsumerAutoConfiguration.java)은 최초 1회·재시도 3회 정책과 실패 레코드의 DLT 전달을 제공한다. 재시도 중 성공하거나 소비가 재개되는 경우와 DLT에 격리된 경우를 구분한다.
- 공통 [DltOpsService](../common/kafka/src/main/java/com/stove/common/kafka/ops/DltOpsService.java)의 원본 토픽 재투입, [OutboxOpsController](../common/messaging/src/main/java/com/stove/common/messaging/ops/OutboxOpsController.java)의 DEAD 재큐잉 도구가 이미 있다. Order·License의 운영 HTTP 설정은 기본 false이며 Gateway도 라우팅하지 않는다. 도구의 존재와 안전하게 사용할 수 있는 운영 절차·끝까지 회복되는 실제 여정의 검증은 구분한다.
- License의 고객 API는 본인 라이브러리 조회다. 주문별 지급/확인 상태와 실패 이벤트를 연결하는 별도 업무 조회·대조 작업자는 현재 없다. 어떤 조회·절차가 필요한지는 기존 도구 검증 결과에 따라 최소 범위로 정한다.

정상적인 트랜잭션 동작에서는 단순 프로세스 중단으로 License만 저장되고 지급 확인용 Outbox가 빠지는 구조가 아니다. at-least-once 전달·DLT는 업무 성공 자체를 뜻하지 않지만, 기존 원본 이벤트가 남아 있으면 그 이벤트의 재처리가 우선이다. 지급 확인만 누락된 경우 OrderPaid 하나를 다시 보내면 된다고 가정하지 않고 원래 LicenseGranted의 상태를 확인한다.

## 우선 검증할 기존 복구 경로

| 중단 위치 | 기존 복구 수단 | 확인할 최종 결과 |
|---|---|---|
| Order의 OrderPaid 발행이 DEAD | 같은 Outbox 이벤트를 재큐잉 | 정상 License 지급과 Order 지급 확인까지 진행 |
| License의 OrderPaid 소비가 DLT | 원래 OrderPaid를 원본 토픽에 재투입 | 같은 구매의 보유권 1건과 지급 확인 |
| License의 LicenseGranted 발행이 DEAD | 같은 Outbox 이벤트를 재큐잉 | 기존 보유권을 유지하고 Order 지급 확인 진행 |
| Order의 LicenseGranted 소비가 DLT | 원래 LicenseGranted를 원본 토픽에 재투입 | 지급 확인 저장과 대기 중 환불의 기존 정지 요청 연결 |

복구 도구 자체의 발행 실패·여러 레코드 중 일부 처리·재처리 도중 종료와 재실행도 확인한다. 원본 발행 확인과 재투입용 오프셋 처리, 중복 실행이 업무 누락·중복 지급을 만들지 않는지 검증하고 발견한 결함만 수정한다. 도구가 이미 있다는 이유로 이 안전성이 검증됐다고 간주하지 않는다.

원본 이벤트가 없거나 구매·지급 이력이 모순되는 건은 별도 확인 대상으로 남긴다. 별도 데이터 재구성 기능의 필요성은 이런 공백이 확인된 뒤 다시 제안한다.

## 첫 쟁점 — 재시도 소진 뒤 누가 재처리를 시작할 것인가

아래 재제안에서 **A를 선택했다.** 정상적인 일시 장애의 자동 재시도는 양쪽 모두 기존 동작을 사용한다. 선택 대상은 재시도가 소진되어 DEAD/DLT로 남은 건의 후속 재처리다.

| 선택 | 고객·운영 흐름 | 개발·운영·고객 비용 |
|---|---|---|
| A. 기존 복구 경로 검증·운영 절차 완성 — 추천 | 대기 중 주문과 실패 이벤트를 연결해 확인하고, 운영자가 원인 해결·원본 대조 후 기존 재큐잉/재투입을 실행한다. 최종 지급·확인 결과를 기록한다. | 기존 도구의 결함과 필요한 조회·실행·기록만 보완해 개발 범위를 줄인다. 재시도 소진 건은 운영자 조치까지 고객이 기다릴 수 있고, 운영 대응이 필요하다. |
| B. 같은 복구 경로의 조건부 자동 재처리까지 구현 | A의 검증·운영 확인에 더해, 안전하게 재처리할 조건을 만족하는 실패 이벤트는 시스템이 기존 경로로 재투입한다. | 일시 장애가 길어져 재시도를 소진한 건도 더 빨리 회복할 수 있다. 대상 분류·장애 해소 판단·반복 상한·동시 실행 조율·실패 관측의 개발과 운영이 추가된다. |

추천은 **A**다. 기존 복구 도구가 실제로 끝까지 회복시키는지 먼저 확인하면 별도 지급 경로의 중복 구현을 피하고, 자동화가 필요한 실패 유형도 근거를 가지고 정할 수 있다. 정상 일시 장애는 이미 자동 재시도가 맡는다. 이번에는 소진 건의 발견·안전한 재처리·결과 확인을 완성하는 것이 구체적인 단위 작업이다. 이는 당시 Codex의 추천 이유이며 사용자가 밝힌 선택 이유가 아니다.

어느 쪽도 모든 DEAD/DLT의 무제한 재투입이나 모순 기록 덮어쓰기를 전제하지 않는다. 별도 보유권 재생성·새 확인 이벤트 생성은 현재 범위로 확정하지 않았다. 재처리 주체 선택 뒤 필요한 조회·접근 통제·실행 기록을 설계하며, B의 자동화 조건·기간·반복 횟수는 별도 정책 논의 없이 미리 정하지 않는다.

## 유지할 기존 계약

- 원래 PAID·주문 금액·구매자·상품·결제/요청 식별자와 불변 구매 스냅샷을 보존한다. 현재 Catalog 가격·제목으로 구매 근거를 다시 만들지 않는다.
- 실제 보유권 정본은 License, 주문의 지급 확인은 Order가 소유한다. 외부 호출의 포트/어댑터·설정 조립과 이벤트 Mapper 경계를 유지한다.
- 이미 지급된 구매의 재확인은 현재 정지 상태를 ACTIVE로 바꾸지 않는다. 환불 실패 후 실제 복구를 담당하는 기존 별도 계약과 구분한다.
- 같은 구매의 중복 지급과 다른 주문·구매자·상품에 귀속된 식별자 재사용을 막는다. 기존 Inbox·업무 근거가 모순되면 누락으로 추정해 채우지 않는다.
- 원래 이벤트 재전달은 원래 식별자·본문을 보존하는 기존 계약을 따른다. 확인할 원본 근거가 사라진 DB 유실·전체 재구축은 이번 누락 복구와 구분한다.

위 항목은 완료된 모듈과 기존 합의의 제약이다. 재처리 주체와 새 업무 계약의 미결 부분을 대신 확정하는 테스트 기대값으로 사용하지 않는다.

## 실행·검증 완료

[결정 37](decisions.md#license-event-recovery)의 A를 기록하고 관련 TDD·단위/아키텍처·실제 DB/Kafka·일곱 앱의 역할별 여정을 OCI 격리 환경에서 완료했다. [최종 1,951건·실제 업무 결과·실행본 무결성·정리 기록](license-event-recovery-verification.md)과 [운영 재처리 절차](license-event-recovery-runbook.md)를 따른다.

실제 PG 금융 동작은 0건이다. 환불은 지급 확인 대기를 벗어나 실제 정지·PG 계약 픽스처 REQUESTED까지 연결됐으며 금융 완료로 표시하지 않는다. 조건부 자동 재투입·원본 부족/모순의 권한 재구성·P4 재구매·G3 정산과 G2 전체 종료는 별도 범위다.
