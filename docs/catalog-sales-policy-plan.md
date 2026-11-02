# 판매 중지·재개와 관리자 판매 제한

2026-11-02. [결정 47](decisions.md#sale-suspension-existing-order-terms)의 판매자 중지 A와 이후 [독립 권한·관리자 승인 접수 제한](decisions.md#separate-seller-and-platform-sale-controls)을 구현한다. 사용자가 지정한 현재 실행 환경은 로컬 OrbStack이며 이전 OCI 검증·자원 수치와 구분한다. 할인 도입의 미결 논의는 [Q13](domain/open-questions.md#q13)에 보존한다.

## 선택한 업무 경계

| 조건 | 소유·권한 | 새 주문 | 기존 PENDING의 새 승인 접수 | 이미 확정된 승인 진행 |
|---|---|---|---|---|
| 판매 가능 | 판매자 ON_SALE·관리자 제한 없음 | 허용 | 원래 주문 금액·기한으로 허용 | 결과 확인 유지 |
| 판매자 자율 중지 | CREATOR 본인의 불변 소유 상품 | 거절 | 원래 주문 금액·기한으로 허용 | 결과 확인 유지 |
| 관리자 판매 제한 | ADMIN의 별도 제한 권한 | 거절 | 제한 중 거절 | 기존 영수증·결과 확인 유지 |

관리자는 판매자의 판매 의사를 대신 바꾸지 않는다. 판매자의 ON_SALE과 플랫폼 제한 여부를 독립 저장하고 공개 상태는 ON_SALE·STOPPED·BLOCKED로 표현한다. 관리자가 해제해도 판매자가 STOPPED이면 계속 STOPPED다. 새 콘텐츠 공개는 어느 제한도 해제하지 않는다. 기존 목록·상세의 노출과 License·Download는 변경하지 않으며, 숨김·권한 회수·긴급 파일 차단·영구 종료 정책은 후속이다.

## API와 저장

| 메서드·경로 | 권한·동작 |
|---|---|
| GET /api/v1/products/by-code/{code}/sales | CREATOR의 자기 상품 판매 의사·플랫폼 제한·현재 버전 |
| PUT /api/v1/products/by-code/{code}/sales | CREATOR 본인의 saleStatus=ON_SALE/STOPPED와 expectedCatalogVersion |
| GET /api/v1/admin/products/{code}/sales/restriction | ADMIN의 현재 두 조건과 버전 |
| PUT /api/v1/admin/products/{code}/sales/restriction | ADMIN의 restricted=true/false와 expectedCatalogVersion |

JWT roles·aud/issuer 검증과 sub를 사용하고 판매자 소유권은 Catalog ownerSubject로 대조한다. 타인 상품은 404, 다른 역할은 403, 비로그인은 401이다. restricted 누락은 false로 해석하지 않고 400으로 거절한다. 민감한 판매 관리 응답은 no-store다.

상품 행 잠금 뒤 버전을 대조한다. 현재 버전의 동일 상태 요청은 변경·감사·Outbox를 만들지 않는다. 이미 사용한 옛 버전은 동일 본문 재전송이어도 409이며 현재 상태를 조회해 후속 명령을 결정한다. 변경 영수증을 별도로 재발급하는 명령 ID 프로토콜은 추가하지 않았다.

Catalog V12는 기존 공개 상품을 ON_SALE·관리자 제한 없음으로 이행하고 catalogVersion을 현재 contentVersion으로 초기화한다. 이후 판매 상태와 새 공개본 모두 Catalog 버전을 증가시키며 콘텐츠 버전보다 작아지지 않는다. 상태·버전, JWT sub/권한·변경 전후 조건/버전·시각의 불변 catalog_sale_change와 공개 사본 Outbox를 같은 트랜잭션에 저장한다. 기존 출시 원장과 마이그레이션은 보존한다.

## Store 사본과 재전달

공개 상세·목록·CatalogProductPublished의 공개 사본에 saleStatus와 catalogVersion을 추가한다. 상태 변경에는 콘텐츠 버전을 올리지 않는다. Store V2는 기존 사본을 같은 기준으로 초기화하고 catalogVersion으로 역순·중복·동일 버전 충돌을 판정한다. 같은 콘텐츠 버전의 자료 변경이나 새 Catalog 버전의 콘텐츠 후퇴도 거절한다.

과거 공개 이벤트처럼 두 새 필드가 모두 없는 JSON은 기존 콘텐츠 버전·ON_SALE로 해석한다. 이 과거 사본은 더 높은 Catalog 버전의 중지·제한을 덮어쓰지 못한다. Order의 현재 HTTP 입력은 saleStatus 누락·알 수 없는 값을 거절한다. Store 초기 동기화와 Kafka는 같은 적용 경로를 사용한다.

## Order·Payment 승인 경계

신규 주문은 Catalog 정본이 ON_SALE일 때만 생성한다. 동일 requestId의 기존 주문 조회는 기존 계약대로 Catalog 없이 원래 주문을 반환한다. 상품 가격·콘텐츠 변경이나 판매자 STOPPED는 기존 주문을 재계산하거나 기한을 연장하지 않는다.

새 승인 접수의 AdmitOrderPayment는 Order 트랜잭션·잠금 밖에서 Catalog를 조회하고 상품 ID·코드와 관리자 BLOCKED를 확인한다. 이미 저장된 접수 영수증은 다시 승인하지 않고 최초 결과를 반환한다. 실제 접수·기한·만료 판정은 기존 RequestOrderPaymentProcessing의 같은 주문 잠금 뒤 서버 시각으로 수행한다. 만료 시각과 같으면 새 접수를 거절하고, 이미 승인 진행이 확정된 건은 시간만으로 만료시키지 않는다.

신규 주문과 새 승인 접수의 판매 제한 관측 지점은 Catalog HTTP 응답의 정본 읽기다. 이 읽기와 Order DB 저장은 서로 다른 트랜잭션이다. 제한 변경이 완료된 뒤 시작한 새 조회는 제한을 반영한다. 조회 중인 접수와 제한 변경이 겹치면 확인한 정본에 따라 진행할 수 있으며, Catalog 제한 완료 응답 시각과 모든 진행 중 Order 쓰기를 하나의 원자 경계로 맞추는 분산 잠금·예약 프로토콜은 구현하지 않았다. 이 보장 범위를 즉시 전역 차단으로 설명하지 않는다.

Order는 관리자 제한을 PRODUCT_SALE_RESTRICTED 409로 구분한다. Payment는 이를 원구매 충돌/ORDER_EXPIRED와 구분해 승인 미접수 REQUESTED·ORDER_SALE_RESTRICTED·같은 명령과 원래 기한을 내구 보존한다. PG 승인 POST·송신 준비는 하지 않는다. 기존 재확인 주기로 제한 해제 여부를 확인하며 기한 내 해제되면 같은 진행을 접수한다. Order가 만료를 확인하면 금융 실패 이벤트·새 결제 ID 없이 종료한다. 이미 접수한 영수증은 이후 제한·조회 장애로 취소하지 않는다.

## 적용 순서

현재 작업은 배포를 수행하지 않는다. 이후 적용할 때 Catalog의 새 공개 필드·스키마와 Store의 새 사본 버전을 먼저 준비하고, Payment의 제한 응답 분류를 Order의 새 거절 응답보다 먼저 적용한다. Gateway의 판매 관리 쓰기 경로는 관련 서비스 적용 후 연다. 그 전에는 서비스 직접 호출로 판매 상태를 변경하지 않는다. 새 Order는 saleStatus 없는 옛 Catalog 응답을 거절하며, 옛 Payment는 새 제한 응답을 일반 충돌로 분류할 수 있으므로 임의 혼합 버전을 지원한다고 설명하지 않는다.

## 검증 범위와 실행 기록

도메인·HTTP/Gateway 역할·입력 계약 → 단위/아키텍처 → OrbStack 실제 MySQL/Kafka·HTTP 집중 → 관련 최종 check와 bootJar 순서로 진행한다. runs/catalog-sales-20261102에 Red/Green·오류 수정·최종 XML·자원 전후를 보존한다. 최초 OrbStack 컨테이너·볼륨은 각각 0개다. Testcontainers는 별도 컨테이너·DB·무작위 포트를 사용하고 기존 자원에 대한 전체 prune/compose down은 수행하지 않는다.

최초 새 도메인 테스트는 판매 상태·Catalog 버전·신규 주문 차단의 미구현으로 컴파일 Red였다. 관리자 독립 제한과 승인 접수 거절 분류도 먼저 Red를 확인했다. Catalog JWT HTTP는 예상 401 대신 기존 403, Gateway는 새 경로 부재로 실패하는 Red를 확인했다. 최초 Gateway 검사의 Cache-Control 인자 타입 오류와 Store CHECK 위반을 DataIntegrityViolationException으로 기대한 오류는 픽스처 수정으로 구분한다. MySQL 실제 CHECK 위반은 3819를 대조한다.

<a id="verified-20261102"></a>

### 최종 완료 — 2026-11-02 로컬 OrbStack

관련 단위·아키텍처 1,237건, 통합 471건, 별도 이행 22건의 **1,730건**을 통과했다. 실패·오류·건너뜀은 0건이다. Catalog·Store의 이행 검사는 각 integrationTest에 포함되며 아래 별도 이행 열은 Order·Payment의 migrationTest다.

| 모듈 | 단위·아키텍처 | 통합 | 별도 이행 | 합계 |
|---|---:|---:|---:|---:|
| 공통 Kafka | 232 | 3 | 0 | 235 |
| Catalog | 55 | 24 | 0 | 79 |
| Store | 55 | 13 | 0 | 68 |
| Order | 383 | 183 | 13 | 579 |
| Payment | 380 | 246 | 9 | 635 |
| Gateway | 132 | 2 | 0 | 134 |
| 합계 | 1,237 | 471 | 22 | 1,730 |

최초 전체 게이트는 필터·명세 갱신 옵션 없이 다음 명령을 통과했다. 22분 29초는 이 명령의 경과 시간이며 성능 비교나 전체 작업 시간이 아니다.

```bash
CI=true DOCKER_HOST=unix:///Users/wltjq12039035/.orbstack/run/docker.sock \
./gradlew --max-workers=2 -Pstove.infraTest.maxParallel=1 \
  :common:kafka:check :apps:catalog:check :apps:store:check \
  :apps:order:check :apps:payment:check :apps:gateway:check \
  :apps:catalog:bootJar :apps:store:bootJar :apps:order:bootJar \
  :apps:payment:bootJar :apps:gateway:bootJar --console=plain
```

그 뒤 관리자 BLOCKED의 실제 Kafka→DB→공개 조회와 해제 경로를 Store 통합 테스트 한 파일에 추가했다. 최초 보강 검사는 수신 전 404를 기다리지 않고 즉시 실패시킨 테스트 관측 오류였다. 사본 생성까지 저장소 Optional로 기다리도록 고친 뒤 CI=true의 `:apps:store:check :apps:store:bootJar`를 필터·명세 갱신 없이 1분 7초에 통과했다. 운영 코드·스키마·설정은 첫 게이트 후 변경하지 않았다. 최종 표는 Store의 마지막 68건과 나머지 모듈의 최초 전체 성공 XML을 사용하며, 앞선 Store 67건·집중 10건·재실행을 중복 합산하지 않는다.

Catalog·Store API 스냅샷은 의도한 변경으로 갱신한 뒤 위 일반 게이트에서 그대로 대조했다. 권한·소유권·누락 제한 값·오래된 버전, 두 제한의 독립 해제, 행 잠금 경합·감사/Outbox 실제 쓰기 롤백, 동일 콘텐츠의 사본 갱신·초기 동기화·Kafka 역순, 기존 주문·최초 접수 영수증·금액/기한 보존, 관리자 제한 중 PG POST 없음·해제 뒤 같은 명령 성공·기한 뒤 안전한 만료를 확인했다. 비어 있지 않은 V11 Catalog·V1 Store 자료의 값·소유자/공개본·버전 이행도 검증했다.

최종 빌드/소스 입력 **1,320개**의 해시가 검증 자료와 일치했다. 최초 전체 게이트 이후 Store 테스트 한 파일만 변경한 기록은 `followup-input-change.json`과 `source-manifest-first-gate.json`에 보존한다. 5개 bootJar를 확인했고 운영 JAR 안에 테스트 클래스·Fixture·test-fixtures JAR가 없다. 각 SHA-256은 `verification-summary.json`에 있다.

최초 집계 스크립트는 매개변수 테스트의 표시명 충돌을 동일 테스트로 오인했고 별도 test-fixtures 산출물도 bootJar 후보에 포함했다. 원본 집계는 `verification-summary-first-attempt.json`에 보존했다. 보고서 내 사례 위치로 테스트 실행을 집계하고 BOOT-INF가 있는 실행 JAR만 식별하도록 고쳤다. 최종 보고서 사례 1,730개와 XML 합계가 일치하고 태스크 간 표시명 충돌은 없으며, 앱 검사를 다시 실행한 것으로 계산하지 않는다.

OrbStack 기존 컨테이너·볼륨은 각각 **0→0개**이며 전체 prune·공유 down·외부 PG 금융 호출·OCI 접속/배포는 수행하지 않았다. 테스트 이미지/Gradle 캐시는 개발 캐시로 남긴다. 원자료는 Git 제외 `runs/catalog-sales-20261102/`의 명령 로그·실패 XML·최종 XML·소스 manifest·JAR 해시·자원 전후·집계 스크립트다.

새 Catalog/Gateway 판매 경로의 역할 검사는 JWT decoder 대역으로 roles/sub를 공급하고 실제 보안 필터/HTTP를 사용한다. 실제 Auth 로그인·운영 사용자 전체 여정이나 실제 PG 승인·취소를 새로 검증한 것은 아니다. 다운로드·보유권 차단과 할인 엔진은 구현하지 않았고, 앞서 명시한 Catalog 정본 읽기/Order 쓰기의 별도 트랜잭션 경계와 G4 전체 미완료를 유지한다.
