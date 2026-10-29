# Order 만료 결과의 Payment 전달

2026-10-29 구현·로컬 검증 완료. [서버 승인 연결](payment-confirmation-plan.md) 다음의 G1 구현이다. 사용자의 `다음` 요청으로 기존 합의의 연결을 진행한다. 30분의 Order 승인 접수 기한·진행 중 시간만으로 종료 금지·원래 3시간 복구 창은 유지한다. 새 금융 구제·선물/보관함 정책을 결정하는 작업이 아니다.

## 배경·현 상태·대안

예를 들어 2시에 주문을 만들고 결제창에서 이탈하면 2시 30분 이후 Order가 EXPIRED를 확정한다. 이전 구현은 이를 Payment에 전달하지 않아 내 결제에는 PENDING이 남고, 승인 결과·실패 이력 조회 예약도 남았다. 새 승인은 Order가 거절하므로 이 문제는 상태 전달과 불필요 조회 정리의 공백이다.

- 이벤트 전달은 Order 변경과 Outbox를 원자 저장하고 기존 Kafka·Inbox로 재전달/중복을 처리한다. 별도의 상태 기록과 이벤트 계약·경합 검증이 필요하고 Payment 반영까지 지연이 생길 수 있다. 고객 조회 때 Order HTTP를 호출하지 않는다.
- Payment의 주기적 Order 조회도 가능하다. 별도 이벤트는 줄지만 종료 확인 HTTP가 계속 발생하고 Order 장애가 정리 지연으로 이어진다. 고객 조회마다 직접 확인하면 읽기 가용성에도 영향을 준다.

현재 모듈들이 쓰는 Outbox·Kafka·Inbox 구조를 사용해 이벤트 전달을 구현한다. 전달 수단의 구현 선택이며 이미 합의한 만료 판단 주체·기한을 다시 정하지 않는다. 구현·테스트·빌드는 로컬에서, 인프라는 격리 OrbStack을 사용한다.

## 계약과 구현

- `OrderExpired`는 주문 번호·원래 OrderPlaced ID·구매자·청구액·통화·고정 기한·만료 확정 시각을 전달한다. OrderPlaced와 같은 ORDER topic·주문 번호 키를 사용한다. 금융 실패 이벤트가 아니다.
- 자동 스캔, 기한 후 승인 접수 거절, 검증된 실패 뒤 기한 후 종료의 세 유스케이스가 최초 EXPIRED 전이와 같은 트랜잭션에 Outbox를 저장한다. 반복 만료·과거 실패 종료 재전송은 새 통지를 발행하지 않는다. 서비스가 조율하며 도메인은 이벤트 전송을 수행하지 않는다.
- Payment의 OrderExpiryMapper가 봉투 ID·유형·키와 본문·값을 검증해 내부 OrderExpiryInput으로 변환한다. ApplyOrderExpiry가 Inbox·원구매 대조·잠금·상태 변경을 함께 저장한다.
- MySQL 행 생성/경합 제어 SQL은 core.port의 OrderPaymentLifecycleLocks를 구현하는 JDBC adapter에 두고 config에서 조립한다. 서비스와 도메인은 DB 공급자의 upsert 문법을 포함하지 않는다.
- V31의 `payment_order_lifecycle`은 주문별 잠금과 최초 만료 근거다. OrderPlaced와 OrderExpired 수신이 모두 같은 행을 먼저 잠근다. 아직 Payment가 없더라도 만료 기록을 보존한다. 뒤늦은 OrderPlaced는 원래 구매 조건과 대조해 조회/승인 예약 없는 EXPIRED Payment를 만들며 기존 만료를 열지 않는다.
- 이 기록은 Parent Payment 부재 때도 필요하므로 Payment 삭제에 연결된 cascade로 지우지 않는다. 테스트 정리는 격리 DB에서 이 테이블도 명시적으로 비운다. 운영 데이터의 자동 삭제·보존 기간 정책을 추가한 것은 아니다.
- 승인 작업이 없는 PENDING만 EXPIRED로 정리하고 승인 결과·실패 이력 조회 예약을 비운다. 과거 개별 실패 이력·구매 스냅샷은 보존하고 금융 실패·환불·권한 회수 이벤트를 만들지 않는다.
- 접수만 된 REQUESTED 작업은 작업자가 Order 만료 근거를 확인해 PG 호출 없이 EXPIRED로 닫고 암호문을 제거한다. 이미 진행 중인 작업은 수신기가 즉시 종료하지 않는다. 기존 작업자가 실패·원래 종료 영수증을 확인하도록 유지한다.
- 기한 내 실패 종료의 최초 영수증이 PENDING이더라도, 이후 실제 Order 만료 통지를 받았다면 실패 확인을 끝낸 Payment를 다시 PENDING으로 남기지 않는다. 최초 종료 영수증·실패 시도는 바꾸지 않는다.
- 만료와 승인 진행이 모순되면 추가 POST를 멈추고 GET·기존 근거를 유지한다. 이미 관측한 승인 근거 없는 금융 이상을 지우지 않는다. 확정 금융 결과가 있는 Payment는 덮어쓰지 않고 만료 통지와 충돌 표시를 보관한다. 별도 취소·환불·운영 종결 정책은 추가하지 않는다.

공개 API와 명세의 형태는 바꾸지 않는다. 기존 내 결제·승인 진행 조회의 EXPIRED를 사용한다. 이미 합의한 본인 조회와 새 주문 유도 계약을 유지한다.

## 검증 계획과 진행 근거

도메인 최초 7건 중 5건의 실제 실패를 확인했다. 원구매·기한·만료 ID/시각의 불변성과 늦은 주문 생성·중복을 구현해 통과시킨다. 원자료는 `runs/order-expiry-propagation-20261029/red-domain`이다.

실제 MySQL·Kafka에서 순서 역전·동시 생성/만료·중복·원구매/ID 오용·실제 flush 롤백·정상 Kafka 리스너를 검증한다. Order는 세 발행 경로·같은 주문의 동시 만료·Outbox 롤백을, 승인 작업자는 미접수 정리·실패 응답 유실과의 경합·확정/비정상 금융 근거 보존을 검증한다. 기존 Order·Payment·공통 Kafka 회귀와 두 모듈 bootJar를 실행한다. 실제 TEST 채널·거래·기존 실행 환경은 변경하지 않는다.

첫 집중 실행은 Order 생명주기 20건·승인 흐름 21건을 통과했다. 새 Payment 만료 전달 10건 중 정상 Kafka 대기 1건은 메시지 도착 전 Optional.orElseThrow가 재시도 대신 즉시 실패하는 테스트 오류였다. Optional에 대한 재시도 가능한 assertion으로 고쳤으며 금융 업무 Red로 합산하지 않는다. `first-focused`에 원자료를 보존했다.

## 최종 검증 완료

2026-10-29 로컬 OrbStack에서 OpenAPI 갱신 옵션 없이 전체 회귀·빌드를 마쳤다. 마지막 DB 어댑터 정리 후 아키텍처 검사에서 어댑터의 Transactional 선언이 기존 서비스 경계 규칙에 걸려 수정했다. 트랜잭션 선언은 서비스에 유지하고 어댑터는 열린 서비스 트랜잭션 안에서만 실행되는지 확인한다. 이 경계 수정은 별도 금융 업무 Red로 세지 않는다.

```bash
./gradlew :common:kafka:test :apps:order:test :apps:payment:test \
  :apps:order:integrationTest :apps:payment:integrationTest \
  :apps:order:bootJar :apps:payment:bootJar --console=plain
```

DOCKER_HOST는 `/Users/wltjq12039035/.orbstack/run/docker.sock`을 사용했다. 최초 전체 실행은 Order·Payment 회귀를 모두 통과했고, 경계 정리 뒤 최종 명령은 Payment 전체 통합을 다시 실행했다. 변경 없는 Order 통합·공통 Kafka는 통과한 Gradle 태스크 결과를 재사용했다. 별도로 보강한 기한 후 실패 종료의 단일 만료 발행 검사는 `:apps:order:test` 전체를 다시 실행해 통과했다. 그 단위 검사 추가로 Order 운영 코드나 통합 테스트는 변경하지 않았다.

| 대상 | 단위 | MySQL/Kafka 통합 | 실패·오류·건너뜀 |
|---|---:|---:|---:|
| Order | 347 | 190 | 0 |
| Payment | 347 | 241 | 0 |
| 공통 Kafka | 221 | 이번 범위 별도 실행 없음 | 0 |
| 합계 | 915 | 431 | 0 |

총 **1,346건**이다. 각 대상의 마지막 전체 JUnit XML을 사용하며, 최초 실패·집중 실행·재실행을 중복 합산하지 않는다. 새 Payment 만료 전달 11건, 승인 흐름 21건, Order 생명주기 20건은 위 전체 수치에 포함된다. 11건에는 서비스 트랜잭션 밖의 저장소 잠금 거절도 포함한다.

- Order와 만료 Outbox의 실제 flush 뒤 롤백, 동시 만료의 단일 발행, 내부 승인 거절과 검증된 실패 종료 발행을 검증했다.
- Payment와 Inbox·만료 기록의 실제 flush 뒤 롤백, 생성/만료 동시 처리, 역순·중복·잘못된 원구매/ID, 실제 Kafka 리스너, 고객 EXPIRED 조회·재접수 거절을 검증했다.
- 접수만 된 승인 정리, 실패 응답 유실 뒤 최초 영수증 보존과 최종 만료, 금융 이상·확정 금융 결과·승인 근거 보존과 추가 POST 중단을 검증했다. 이미 수집한 실패 이력은 삭제하지 않는다.
- 공개 명세는 갱신하지 않았다. 기존 Order·Payment 기본/PortOne 명세와 이전 환불·웹훅·재시도·추적·마이그레이션 회귀를 포함한다. Gateway 소스/경로는 이번 단계에서 변경하지 않았다.

두 실행 JAR 빌드가 성공했다. SHA-256은 다음과 같다.

- Order: `a0f35a7ccddb77c2e71feafc337453dd18a95f4251c6010403c4bb06f6f495c3`
- Payment: `a0c587afdac341a891b6cd41064451c577cf85e07c742abc8150445d76b1767c`

최종 `orbstack` context의 컨테이너·볼륨은 각각 0개다. 공유 인프라 초기화·전체 prune·OCI 접근·실제 PG 호출은 수행하지 않았다. 최종 XML·실행 로그·소스/명세 SHA·JAR SHA와 요약은 `runs/order-expiry-propagation-20261029/final`에 보존했다. 검증 이후 업무 소스는 수정하지 않고 결과 문서만 갱신했다.

## 다음 범위

다음은 실제 TEST 채널의 수동 승인 설정과 새 주문 전체 구매 여정의 검증 범위를 정하는 작업이다. Order·Payment·라이선스 반영까지 두 실제 서비스/역할별 인증과 실제 TEST 거래를 잇는 검증은 위 루프백 계약 검사와 구분한다. 미결 금융 구제 정책을 자동 확정하거나 G1 전체 완료로 기록하지 않는다.
