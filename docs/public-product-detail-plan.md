# 공개 상세 B — 노출 연동과 내부 업무 조회 분리

2026-11-02 사용자는 C로 확장할 여지를 남기되 B를 구현하도록 요청했다. 여기서 B는 기존 검색·목록의 독립 설정을 폐기하는 것이 아니라 **최종 listed=false인 상품의 공개 상세도 거절**하는 정책이다. 후속 질문에서는 숨긴 상품이 ON_SALE이어도 새 주문을 거절하는 안을 선택했다. 기존 주문의 승인 기한·판매자 중지·관리자 판매 제한과 완료 구매의 이용권/다운로드는 기존 계약을 보존한다.

## 범위와 확장 경계

- Catalog/Store 공개 상세는 최종 listed 기준으로 거절하고 없는 상품과 같은 404를 반환한다. 판매자 UNLISTED, AUTO의 판매 중지·관리자 판매 제한, 관리자 노출 숨김을 모두 포함한다. LISTED이고 관리자 노출 숨김이 없으면 공개 상세를 허용한다.
- 모듈 내부 ProductPublicAccess에 공개 상세 판단을 모은다. C를 도입할 때 이 정책과 실제 필요한 상태/사본 계약을 확장할 수 있게 하되 현재 별도 공개 차단 플래그·관리 명령·설정 스위치·정책 레지스트리는 만들지 않는다.
- 공개 조회와 내부 불변/현재 업무 근거 조회를 명시적으로 구분한다. 감사/Outbox 생성·Store 초기 동기화·Order의 구매 조건/기존 승인 조회는 공개 상세의 404에 의존하지 않는다.
- 새 주문에는 Catalog 정본에서 확인한 공개 상세 허용 근거를 전달하고 Order의 새 주문 정책에서 거절한다. 같은 requestId의 기존 주문 재전송과 이미 생성된 주문의 승인 접수는 이 새 주문 검사를 재실행하지 않는다. 관리자 판매 제한은 기존 별도 계약대로 새 승인 접수도 차단한다.
- 상품·주문 DB의 트랜잭션은 분리돼 있으므로 조회 중 상태 변경과 새 주문 생성이 하나의 원자 경계는 아니다. Store도 기존 Kafka 사본의 비동기 반영 범위를 유지한다. 글로벌 즉시 차단·기존 응답/파일 회수를 주장하지 않는다.

## 내부 HTTP 계약

Store는 기존 전용 키로 `/internal/v1/store-products` 전체 목록과 `/internal/v1/store-products/{code}` 상세를 읽는다. 공개 상세는 더 이상 초기 동기화에 사용하지 않는다. 리다이렉트를 따르지 않고 키가 없으면 공개 경로로 우회하지 않는다.

Order는 별도 CATALOG_ORDER_SERVICE_KEY·X-Order-Service-Key로 `/internal/v1/order-products/{code}`를 읽는다. 응답은 주문에 필요한 상품 ID·코드·제목·버전·금액·통화·판매 상태와 공개 상세 허용 근거만 전달한다. 원본 본문·스토리지 참조·소유자·운영 설정은 전달하지 않는다. 키 미설정/거절은 연계 장애이고 고객의 미보유/상품 없음으로 바꾸지 않는다. 연결·읽기 제한과 리다이렉트 금지를 적용한다.

각 서비스 키는 해당 GET에만 권한이 있다. 일반 JWT·다른 서비스 키·없는 키는 거절하며 Gateway와 공개 OpenAPI에 내부 경로를 노출하지 않는다. 새 키는 Catalog/Order에 함께 설정하고 기존 Store 키는 Catalog/Store에 유지한다. 공유 런타임 자동 배포나 자격증명 덮어쓰기는 하지 않는다.

## 검증 계획

로컬 OrbStack의 기존 컨테이너·볼륨은 시작 시 각각 0개로 확인했다. 공개 상세와 새 주문의 Red→관련 단위/아키텍처→실제 Catalog/Store/Order DB·HTTP·기존 승인/Outbox·초기 동기화→필터/명세 갱신 없는 최종 check와 실행 JAR를 확인한다. 별도 스키마·새 외부 이벤트는 도입하지 않는다. 기존 미커밋 자료와 실행 증거를 보존한다. 원자료는 runs/public-detail-b-20261102에 기록한다.

## 현재 동작의 구분

| 상태 | 공개 상세 | 새 주문 | 기존 PENDING의 새 승인 접수 |
|---|---|---|---|
| ON_SALE·listed=true | 허용 | 기존 구매/금액 조건 검사 후 허용 | 원래 조건·기한 유지 |
| ON_SALE·listed=false | 404 | 404로 거절 | 원래 조건·기한 유지 |
| 판매자 STOPPED·AUTO | 404 | 거절 | 원래 조건·기한 유지 |
| 판매자 STOPPED·LISTED | 허용 | 판매 중지로 거절 | 원래 조건·기한 유지 |
| 관리자 판매 BLOCKED | listed에 따라 결정 | 거절 | 기존 관리자 제한 정책으로 거절 |

기존 승인 영수증이 있으면 재전달은 최초 영수증에 수렴한다. 완료 구매의 내 주문/라이브러리와 Download의 내부 빌드 조회에는 이 공개 상세 정책을 적용하지 않는다.

## C로 확장할 때 유지할 경계

Catalog의 공개 상세 판정과 Order 내부 응답의 publicDetailAllowed 생산은 같은 ProductPublicAccess 정책을 사용한다. Order는 이 근거를 현재 새 주문 규칙으로 사용하며 listed 자체를 해석하지 않는다. Store도 공개 상세 판정을 모듈 내부 ProductPublicAccess에 모은다.

C를 실제 선택할 때 필요한 공개 접근 상태와 Store 사본 계약을 추가하고 해당 정책을 확장한다. 공개 HTTP와 업무 내부 조회의 경계, 기존 주문 승인 흐름은 그대로 사용할 수 있다. C의 독립 플래그/관리 API를 미리 구현하거나 C의 세부 정책을 테스트 기대값으로 확정하지 않는다.

## 적용과 검증 경계

이번 산출물은 내부 계약 이동과 공개 상세 B를 함께 포함한다. 구 Store/Order가 공개 상세를 호출하는 동안 Catalog만 먼저 교체하면 숨긴 상품의 초기 동기화/기존 주문 업무 조회가 실패할 수 있다. 적용 시에는 전용 키를 준비하고 정비 시간에 세 서비스를 함께 교체하거나, 내부 경로만 먼저 제공하는 별도 선행 배포 단계를 마련해야 한다. 현재 전환 스위치·공개 경로 폴백은 없다. 이 작업에서 공유 서비스의 실제 배포는 하지 않는다. Order 내부 응답의 publicDetailAllowed가 없는 혼합 버전은 유효한 현재 계약으로 취급하지 않는다. 외부 입력의 잘못된 Boolean은 연계 계약 오류이며 키 미설정·키 거절·타임아웃은 503으로 구분한다.

기존 격리 스택 검증 스크립트에는 매 실행 새 CATALOG_ORDER_SERVICE_KEY를 생성해 Catalog/Order에 전달하는 설정을 추가하고 Python 문법을 확인한다. 기존 스크립트들의 전체 금융/역할 여정을 이번 단위 검사로 대신 주장하지 않는다.

<a id="verified-20261102"></a>

## 2026-11-02 구현·검증 완료

현재 JDK 21과 로컬 OrbStack에서 다음 필터 없는 최종 게이트를 실행해 **BUILD SUCCESSFUL, 12분 24초**를 확인했다. OpenAPI 명세 갱신 모드 없이 실행했으며 Order의 check는 migrationTest도 포함한다.

```sh
PATH=/Users/wltjq12039035/.orbstack/bin:$PATH \
JAVA_HOME=/Users/wltjq12039035/.jdks/jdk-21.0.12+8/Contents/Home \
CI=true \
DOCKER_HOST=unix:///Users/wltjq12039035/.orbstack/run/docker.sock \
STOVE_TEST_MINIO_IMAGE=stove/minio:download-restriction-20261102 \
./gradlew --no-daemon --max-workers=2 -Pstove.infraTest.maxParallel=1 \
  :apps:catalog:check :apps:store:check :apps:order:check \
  :apps:catalog:bootJar :apps:store:bootJar :apps:order:bootJar --console=plain
```

| 모듈 | 단위·아키텍처 | 통합 | 별도 이행 | 합계 |
|---|---:|---:|---:|---:|
| Catalog | 67 | 42 | 통합에 포함 | 109 |
| Store | 58 | 16 | 통합에 포함 | 74 |
| Order | 388 | 184 | 13 | 585 |
| 합계 | 513 | 242 | 13 | **768** |

최종 XML의 테스트 수와 실제 testcase 수를 대조했으며 실패·오류·건너뜀은 모두 0이다. 이전 판매/노출/다운로드 검증 건수를 이번 768건에 더하지 않는다. 최종 게이트 전에 고정한 소스·설정·스크립트 입력 612개의 SHA-256도 실행 후 모두 일치한다. 세 실행 JAR의 SHA-256을 보존하고 테스트 클래스·픽스처/테스트 의존성의 혼입이 없음을 확인했다.

- Red: Catalog/Store의 숨긴 공개 상세 거절을 먼저 실패로 확인했고, Order는 새 공개 접근 근거의 생성자/거절 계약을 먼저 추가했다. 로그·실패 XML은 runs/public-detail-b-20261102의 red.log·red-order.log·failures에 보존한다.
- Catalog 실제 DB/HTTP: 숨김/복원, STOPPED AUTO와 명시적 LISTED, 관리자 숨김의 재출시 유지, public 404/no-store, 내부 Store 상세·최소 Order 근거, 일반 JWT/누락/교차 키 거절과 내부 OpenAPI 비노출을 확인했다. 감사/Outbox가 숨김 상세 때문에 롤백되지 않는 기존 검사도 통과했다.
- Store 실제 DB/HTTP: 숨긴 사본은 유지하면서 공개 상세는 404, 새 버전의 재노출은 200, DB 검색/페이지 필터·전체 초기 동기화를 확인했다. HTTP 클라이언트는 내부 상세 키 전달·키 미설정의 무호출·리다이렉트 금지와 필수 listed를 확인했다.
- Order 실제 DB/HTTP: 숨긴 ON_SALE의 직접 새 주문은 404이고 주문/Outbox를 만들지 않는다. 먼저 만든 주문은 숨김 후 같은 requestId 재전송에 수렴하고 승인 접수·원래 금액/기한·기존 영수증과 판매 중지/관리자 제한 규칙을 유지한다. HTTP 어댑터는 필수 Boolean·키 인증 장애·리다이렉트를 검증했다.
- 원자료는 runs/public-detail-b-20261102의 final.log·final-xml·source-manifest.json·verification-summary.json·resource-comparison.json에 보존한다. 검증 스크립트 17개의 Python 문법을 확인했으며 전체 스택 스크립트는 이번 작업에서 실행하지 않았다.

MinIO는 앞선 다운로드 제한 작업에서 고정 버전 소스로 만든 로컬 이미지 별칭을 사용했다. 실제 Auth/Gateway까지 포함한 전체 역할 여정·실제 PG 금융 동작·공유 서비스 배포는 수행하지 않았다. 기존 License/Download 동작 보존은 이 작업의 공개 접근 검사 적용 범위에서 분리했으며 해당 모듈의 전체 검사를 다시 실행한 것으로 주장하지 않는다. G4 전체와 독립 공개 접근 C는 후속 범위다.

검증 전후 컨테이너·볼륨은 각각 **0→0**이며 Testcontainers가 만든 자원만 자체 생명주기로 정리됐다. 프로세스 종료 직후 잠시 남은 해당 실행의 Ryuk은 스스로 종료했으며 즉시 관측본도 별도로 보존했다. prune·공유 Compose 종료·기존 볼륨 삭제는 실행하지 않았다. 이전 실행 증거·이미지와 기존 미커밋 자료를 보존했다.
