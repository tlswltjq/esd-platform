# 상품 검색·목록 노출 — 독립 설정과 판매 기반 기본값

**후속 정책 적용:** 이 문서의 548건 검증은 검색·목록 설정을 구현한 시점의 기록이다. 이후 사용자가 [공개 상세 B](public-product-detail-plan.md)를 선택해 최종 listed=false인 상품의 공개 상세와 새 주문도 404로 거절하도록 확장했다. 아래의 직접 상세 유지·공개 상세를 이용한 초기 동기화 설명은 당시 범위이며, 현재 Store는 보호한 내부 전체 목록과 내부 상세를 함께 사용한다. 독립 노출 설정·권한과 AUTO 기본값은 유지한다.

2026-11-02 사용자는 **C(독립 노출), 기본 동작은 B(판매 가능 상품만 노출)**를 선택했다. 후속 권한 질문에는 **판매자 선택 + 관리자의 독립 강제 숨김**을 선택했다. 이 두 선택은 다시 묻지 않는다. 구현·검증은 현재 지정한 로컬 OrbStack 격리 자원에서 수행하며 기존 미커밋 판매/다운로드 작업을 보존한다.

## 정책과 범위

- 판매자 선호는 `AUTO`, `LISTED`, `UNLISTED`다. 신규·기존 상품의 기본값 AUTO는 최종 saleStatus=ON_SALE일 때만 검색·목록에 포함한다. 판매자 STOPPED·관리자 판매 BLOCKED는 AUTO에서 제외되며 판매 재개 시 자동으로 다시 포함한다.
- LISTED는 판매 상태와 독립적으로 검색·목록에 포함하고 UNLISTED는 제외한다. 명시적 선택은 판매 중지/재개와 새 공개본에서도 유지한다. AUTO로 되돌리면 현재 판매 상태를 다시 따른다.
- ADMIN의 `platformListingHidden=true`는 판매자 선호와 관계없이 제외한다. 판매자의 LISTED/재출시가 관리자 숨김을 해제하지 않으며 관리자 해제도 판매자의 UNLISTED를 바꾸지 않는다.
- 최종 규칙은 `!platformListingHidden && listingPreference.isListed(saleStatus)`다. 개인별 중복 구매 가능 여부·다운로드 정지 여부를 이 규칙에 섞지 않는다.
- 이번 계약은 검색·목록의 발견 가능성이다. 직접 Catalog/Store 상품 상세 URL은 기존대로 조회되며 응답의 `listed`로 현재 노출 값을 제공한다. 구매 허용·기존 주문·개인 License·상품 다운로드 제한은 기존 독립 계약을 유지한다. 직접 상세 접근 차단/콘텐츠 제거는 아직 선택하지 않았다.

| 판매자 설정 | 판매 상태 | 관리자 숨김 없음 | 관리자 숨김 있음 |
|---|---|---|---|
| AUTO | ON_SALE | 포함 | 제외 |
| AUTO | STOPPED/BLOCKED | 제외 | 제외 |
| LISTED | 어느 판매 상태든 | 포함 | 제외 |
| UNLISTED | 어느 판매 상태든 | 제외 | 제외 |

## 정본·변경·감사

Catalog V14는 상품에 listingPreference=AUTO·platformListingHidden=false를 추가하고 기존 공개본·소유자·판매/다운로드 상태·버전은 보존한다. 콘텐츠 공개본을 수정하지 않고 catalogVersion으로 노출 변경의 순서를 정한다.

| 경로 | 역할·입력 |
|---|---|
| GET/PUT `/api/v1/products/by-code/{code}/listing` | CREATOR 본인의 불변 ownerSubject와 JWT sub 대조. PUT preference·expectedCatalogVersion |
| GET/PUT `/api/v1/admin/products/{code}/listing/restriction` | ADMIN. PUT hidden(필수 Boolean)·expectedCatalogVersion |

GET은 두 조건·최종 listed·판매 상태·현재 버전을 반환한다. PUT은 상품 행 잠금→버전 확인→해당 권한의 조건만 변경→catalogVersion 증가→불변 catalog_listing_change와 공개 사본 Outbox를 한 트랜잭션으로 저장한다. JWT sub·권한·변경 전후 선호/관리자 조건·버전·시각을 감사한다. 공개 사본에는 최종 listed만 포함하며 소유자·운영 선호/관리자 조건은 공개하지 않는다.

현재 버전의 같은 상태 명령은 새 버전·감사·Outbox를 만들지 않는다. 이전 버전은 동일 본문 재전송도 409이며 현재 GET으로 후속 명령을 정한다. 비로그인 401·역할 위반 403·타인/없는 상품 404·누락/잘못된 입력 400이다. Catalog/Gateway가 각각 권한을 확인하고 관리 응답과 공개 조회는 no-store다.

## 공개 조회·Store 사본·전체 초기 동기화

- Catalog 공개 목록은 정본의 최종 노출 조건을 DB WHERE에 적용한 뒤 페이지/총개수를 계산한다. 직접 상세는 별도 기존 조회다.
- CatalogProductSnapshot에 Boolean listed를 추가하고 새 공개/판매/노출 변경은 실제 최종 값을 명시한다. 과거 JSON에 값이 없으면 기존 saleStatus를 기준으로 AUTO를 복원한다. 과거 판매 필드까지 없으면 기존 ON_SALE 계약을 따른다. 새 HTTP 상세에는 Boolean listed가 반드시 있어야 한다.
- Store V3는 기존 사본에 saleStatus=ON_SALE 기준의 listed를 추가한다. Kafka와 HTTP 초기 동기화는 동일한 적용 경로를 사용하고 catalogVersion의 중복·역순·같은 버전 충돌과 동일 콘텐츠 검사를 유지한다. 콘텐츠가 같아도 더 높은 Catalog 버전의 노출 변경은 반영한다.
- Store 제목 검색과 목록은 DB에서 listed=true를 먼저 적용한다. 숨긴 상품은 검색·페이지·totalElements/totalPages에서 제외되며 사본을 삭제하지 않는다. 숨긴 상품의 직접 상세와 뒤의 재노출을 위해 자료를 유지한다.
- Store 전체 초기 동기화는 공개 목록과 분리한 `GET /internal/v1/store-products`로 모든 상품 코드를 읽고 기존 공개 상세에서 현재 사본을 받는다. 내부 GET은 `X-Store-Service-Key`와 명시적 `CATALOG_STORE_SERVICE_KEY`로만 허용한다. 미설정·잘못된 키·일반 JWT는 거절하며 다른 내부/관리 경로의 권한을 주지 않는다. Gateway 라우트·공개 OpenAPI에는 추가하지 않는다.
- Store의 서비스 키는 내부 전체 목록 요청에만 전달한다. 공개 상세에는 보내지 않으며 HTTP 리다이렉트를 따르지 않는다. 연결 3초·읽기 10초 제한을 유지한다. 전체 페이지 수집 실패 시 ready가 되지 않는다.

Store 반영은 Outbox/Kafka의 비동기 사본이다. Catalog 변경 직후 Store에서 이전 노출을 잠시 관측할 수 있으며 동시 전역 숨김·조회 결과 철회를 보장하지 않는다. 초기 동기화의 페이지를 하나의 전체 DB 스냅샷으로 묶은 프로토콜은 이번 범위가 아니다. 기존 안정적인 상품 ID 보조 정렬과 버전 적용을 유지한다.

## 구성·적용

Catalog와 Store에 같은 전용 CATALOG_STORE_SERVICE_KEY를 주입한다. 기본 자격증명·공개 목록으로의 자동 우회는 없다. Compose와 기존 격리 검증 스크립트도 이 값을 양쪽에 전달하도록 갱신한다. 검증 스크립트는 매 실행 새 키를 생성하고 출력하지 않는다.

실제 적용은 Store의 초기 동기화/공개 진입을 중단한 정비 구간에서 Catalog V14·새 계약/키와 Store V3·새 클라이언트를 함께 준비한 뒤 Store 전체 동기화 성공을 확인하고 관리자/판매자 노출 경로를 연다. 새 DB에 옛 Store 쓰기 인스턴스가 병행하지 않게 한다. 임의 혼합 버전·무중단 전환을 보장한다고 설명하지 않는다. 이번 작업은 공유 환경 정책·계정·서비스 배포나 실제 PG 금융 동작을 수행하지 않는다.

## 검증 기록

도메인 최초 컴파일 Red→단위/아키텍처→실제 MySQL/Kafka·HTTP와 비어 있지 않은 Catalog V13/Store V2 이행→API 스냅샷 생성→갱신 옵션/필터 없는 관련 최종 check/bootJar 순서로 확인한다. 원자료는 Git 제외 `runs/catalog-listing-20261102/`에 보존한다. 최초 기존 컨테이너·볼륨은 각각 0개다. 최종 수치는 아래에 추가한다.


<a id="verified-20261102"></a>

## 최종 완료 — 2026-11-02 로컬 OrbStack

단위·아키텍처 **489건**, 통합/이행 **59건**, 총 **548건**을 통과했다. 실패·오류·건너뜀은 0건이다. Catalog/Store 이행 검사는 integrationTest에 포함한다. 이전 판매 관리 1,730건·다운로드 제한 348건과 이번 집중 검사/재실행을 더하지 않는다.

| 모듈 | 단위·아키텍처 | 통합·이행 | 합계 |
|---|---:|---:|---:|
| 공통 Kafka | 232 | 3 | 235 |
| Catalog | 66 | 38 | 104 |
| Store | 57 | 16 | 73 |
| Gateway | 134 | 2 | 136 |
| 합계 | 489 | 59 | 548 |

최종 게이트는 테스트 필터·명세 갱신 옵션 없이 다음 명령으로 성공했다. 경과 시간은 4분 16초이며 성능 비교 결과가 아니다.

```bash
PATH=/Users/wltjq12039035/.orbstack/bin:$PATH \
JAVA_HOME=/Users/wltjq12039035/.jdks/jdk-21.0.12+8/Contents/Home \
CI=true DOCKER_HOST=unix:///Users/wltjq12039035/.orbstack/run/docker.sock \
STOVE_TEST_MINIO_IMAGE=stove/minio:download-restriction-20261102 \
./gradlew --no-daemon --max-workers=2 -Pstove.infraTest.maxParallel=1 \
  :common:kafka:check :apps:catalog:check :apps:store:check :apps:gateway:check \
  :apps:catalog:bootJar :apps:store:bootJar :apps:gateway:bootJar --console=plain
```

검증한 업무 경계는 다음과 같다.

- AUTO의 판매 중지/재개·관리자 판매 제한 연동, LISTED/UNLISTED 예외, 관리자 숨김 우선·권한별 독립 해제·새 공개본 보존을 도메인과 실제 Catalog HTTP/DB에서 확인했다. 상세는 계속 조회되며 목록에서만 제외된다.
- Catalog/Gateway의 역할·JWT sub 소유권, 400/401/403/404/409·no-store, 현재 버전의 무변경 명령과 stale 명령, 판매자/관리자의 동시 변경 한 건 수렴을 확인했다. 새로운 역할 검사는 JWT decoder 대역을 사용하며 실제 Auth 계정 로그인 여정과 구분한다.
- 실제 MySQL CHECK로 감사 INSERT와 Outbox INSERT를 각각 실패시켜 상품 선호/버전·감사·이벤트의 롤백과 재시도 성공을 확인했다. 공개 Outbox JSON은 최종 listed만 포함하고 내부 선호·관리자 조건·소유자를 노출하지 않는다.
- Store의 실제 MySQL 검색·정렬·페이지·totalElements 필터, Kafka 노출 갱신과 역순/중복, 초기 동기화에서 이전 사본으로 숨김을 되돌리지 않는 경계를 확인했다. 새 사본의 콘텐츠 버전은 그대로여도 노출 변경을 적용한다.
- 내부 전체 목록은 숨긴 상품도 포함한다. 키 없음/오류·일반 JWT 거절, 전용 키의 다른 내부 경로 거절, Gateway 라우트 부재·공개 OpenAPI 미노출을 확인했다. Store HTTP 대역 검사에서는 키가 내부 목록에만 전송되고 리다이렉트를 한 번도 따라가지 않는 것을 확인했다.
- 비어 있지 않은 Catalog V13·Store V2→최신 이행에서 기존 공개/판매/다운로드 자료·소유자·버전을 보존하고 AUTO/관리자 숨김 없음·판매 상태별 Store listed를 확인했다. 잘못된 선호/Boolean 저장은 실제 CHECK로 거절했다.
- Catalog/Store OpenAPI를 의도한 경로·listed 필드로 생성한 뒤 최종 check에서 갱신 없이 대조했다. 기존 격리 실행 스크립트 7개는 전용 동기화 키 전달을 갱신했고 Python 소스 문법을 검사했다. 이 스크립트들의 전체 구매/PG 여정이나 실제 배포를 이번에 새로 실행한 것은 아니다.

최초 도메인 검사는 미구현 ListingPreference에 대한 컴파일 Red였다. 첫 집중 Catalog 검사의 6건 중 1건은 MySQL 정규화 JSON에 공백 없는 문자열을 기대한 검사 오류였으며 JSON 값을 직접 대조하도록 고친 뒤 집중/최종 게이트를 통과했다. 이 기대값 수정은 업무 롤백 실패와 구분한다.

검증 입력 **414개**의 최종 해시 일치와 XML 테스트 수/사례 수 일치를 확인했다. 세 실행 JAR의 SHA-256과 운영 JAR 내 Test/Fixture·시험 라이브러리 부재는 `verification-summary.json`에 있다. 최종 게이트 시작 후 운영 코드·테스트·설정·명세 입력 변경은 없다.

기존 컨테이너·볼륨은 각각 **0→0개**로 보존했다. 전체 prune·공유 down·OCI 접속·실제 PG 금융 동작·공유 환경 배포는 수행하지 않았다. 개발 캐시는 유지한다. 원자료는 `runs/catalog-listing-20261102/`의 로그·실패/최종 XML·source-manifest.json·JAR 해시·resources-before.json/resource-comparison.json·집계 스크립트다.

이번 검색·목록 단위는 완료다. 직접 상세 URL 접근 차단 정책·공개 콘텐츠 제거·실제 운영 화면/전체 Auth 여정·G4 전체는 후속이며 완료로 확대하지 않는다.
