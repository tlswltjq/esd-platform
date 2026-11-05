# 요구사항: Store (공개 상품 검색·조회 사본)

## 개요

Store는 Catalog 공개 상품의 **검색·조회용 사본**을 MySQL에 보관하고, 방문자에게 제목+장르 검색·목록·상세를 익명으로 제공한다.
사본의 정본은 Catalog이며 Store는 상품 ID·가격·제목을 독자적으로 발급·편집하지 않는다.
정상 변경은 `CatalogProductPublished` 이벤트로 반영하고, 초기 사본 구성과 누락·불일치 복구는 Catalog 정본을 내부 키로 조회해 자동으로 수행한다.
MVP 재작성은 2026-10-15 완료했고, 이후 자동 동기화·복구(2026-11-03)와 장르 분류·AND 검색(2026-11-03)을 추가했다.

## 용어

- **StoreProduct**: Catalog 공개 상품의 조회용 사본. Catalog productId를 PK로, productCode에 유일 제약을 둔다.
- **catalogVersion**: 콘텐츠 버전과 독립적으로 사본의 신구를 판단하는 버전. 시각이 아니라 이 값으로 교체 여부를 정한다.
- **준비 상태(ready)**: 시작 시 전체 초기 동기화를 한 번 성공한 뒤 열리는 메모리 상태. 이 전에는 업무 조회에 503을 반환한다.
- **정본 대조(reconciliation)**: Catalog 내부 조회로 사본의 누락·구버전·동일 버전 내용 차이를 감지·교정하는 주기 작업.
- **AND 검색**: 복수 장르를 선택하면 선택한 장르를 **모두** 가진 상품만 반환하는 조건(결정 75).

## 요구사항

### R1. 공개 상품 검색·목록 조회
**사용자 스토리:** 방문자로서, 공개된 게임을 제목과 장르로 찾고 목록을 보고 싶다. 그래야 원하는 상품을 발견할 수 있다.

#### 수용 기준
1. WHEN `GET /api/v1/storefront/products` 요청이 오면 THEN 시스템 SHALL 로그인 없이 노출 상품 목록을 조회한다.
2. THE 기본값 SHALL q 없음, `sort=NEWEST`, `page=0`, `size=20`이며 page >= 0, 1 <= size <= 100으로 제한한다.
3. THE q SHALL 앞뒤 공백을 제거하고 최대 100자로 제한하며, 빈 값은 전체 목록으로 처리한다.
4. THE 제목 검색 SHALL 대소문자를 구분하지 않는 부분 일치로 처리하고, 파라미터 바인딩과 LIKE 특수 문자 이스케이프를 사용한다.
5. WHEN `genres` 조건이 둘 이상 오면 THEN 시스템 SHALL 선택한 장르를 모두 가진 상품만 반환한다(AND).
6. IF 장르 검색 조건이 생략되면 THEN 시스템 SHALL 기존처럼 모든 노출 상품을 검색한다.
7. IF 검색 장르에 알 수 없는 코드·중복·빈 코드가 포함되면 THEN 시스템 SHALL 400으로 거절한다.
8. THE 장르 조건 SHALL 제목 검색·최종 노출 조건과 함께 DB 페이지·총건수 계산 전에 적용한다.

### R2. 정렬과 페이지
**사용자 스토리:** 방문자로서, 최신순·가격순으로 정렬하고 페이지로 넘겨 보고 싶다. 그래야 많은 상품을 훑어볼 수 있다.

#### 수용 기준
1. THE 시스템 SHALL `NEWEST`, `PRICE_ASC`, `PRICE_DESC` 정렬을 지원한다.
2. WHERE `NEWEST` 정렬에서 THE 시스템 SHALL 최초 공개 시각 내림차순으로 정렬한다.
3. WHERE `PRICE_ASC`·`PRICE_DESC` 정렬에서 THE 시스템 SHALL 가격 오름차순·내림차순으로 정렬한다.
4. THE 마지막 정렬 키 SHALL 모두 productId 내림차순이며, 재출시를 신작으로 취급하지 않는다.
5. THE 목록 응답 SHALL `content`·`page`·`size`·`totalElements`·`totalPages`를 포함한다.

### R3. 상품 상세 조회
**사용자 스토리:** 방문자로서, 상품 코드로 현재 공개된 상세를 보고 싶다. 그래야 구매 전에 내용을 확인할 수 있다.

#### 수용 기준
1. WHEN `GET /api/v1/storefront/products/{productCode}` 요청이 오면 THEN 시스템 SHALL 현재 반영된 공개 상세를 익명으로 조회한다.
2. THE 상세·목록의 공개 필드와 의미 SHALL 기존 Catalog 응답을 따르며, 내부 저장 경로·소유자 정보를 노출하지 않는다.
3. THE 가격 SHALL Catalog의 KRW 정가이며, 주문 금액이나 구매 가능 여부를 판정하지 않는다.
4. THE 조회 사본 SHALL `saleStatus`·`catalogVersion`을 함께 제공한다.
5. WHERE 준비 완료 후 없는 상품 코드에서 THE 시스템 SHALL 404를 반환한다.
6. IF 최종 listed=false인 상품의 공개 상세를 요청하면 THEN 시스템 SHALL 404로 거절한다.

### R4. 장르 기준 목록 조회
**사용자 스토리:** 방문자로서, 선택 가능한 장르 목록을 보고 싶다. 그래야 장르로 검색 조건을 고를 수 있다.

#### 수용 기준
1. WHEN `GET /api/v1/storefront/genres` 요청이 오면 THEN 시스템 SHALL 익명으로 장르 코드·표시명·선택 가능 여부를 제공한다.
2. THE 장르 기준 목록 SHALL 코드에 정의한 `GenreCatalog` 값 객체를 따르며, 실행 중 다른 서비스에서 내려받지 않는다.
3. WHERE 선택 중지된 알려진 코드에서 THE 시스템 SHALL 기존 상품의 검색·표시에 그 코드를 계속 사용할 수 있게 한다.

### R5. Catalog 변경 이벤트 수신과 사본 반영
**사용자 스토리:** Store로서, Catalog의 공개 변경을 받아 사본을 최신으로 유지하고 싶다. 그래야 방문자가 최신 공개본을 본다.

#### 수용 기준
1. WHEN `CatalogProductPublished`(`stove.catalog.v1`)를 수신하면 THEN 시스템 SHALL 그룹 `store-catalog-publication`, 최초 소비 위치 earliest로 처리한다.
2. WHEN 더 높은 catalogVersion이 오면 THEN 시스템 SHALL 공개 필드 전체를 교체한다.
3. WHEN 낮은 catalogVersion이나 중복·역순 이벤트가 오면 THEN 시스템 SHALL 현재 사본을 유지한다.
4. WHEN 같은 버전·같은 공개 데이터가 오면 THEN 시스템 SHALL 변경 없이 성공 처리한다.
5. IF 같은 catalogVersion인데 공개 데이터가 다르면 THEN 시스템 SHALL 계약 위반으로 실패하고 기존 사본을 보존한다.
6. IF 상품 ID·상품 코드 연결이 충돌하면 THEN 시스템 SHALL 계약 위반으로 실패한다.
7. WHERE 콘텐츠 버전이 같아도 판매 상태가 바뀐 경우에서 THE 시스템 SHALL 그 변경을 반영한다.
8. THE 시스템 SHALL 구 `ProductChanged` 등 다른 이벤트 유형을 무시하고 새 유형만 처리한다.
9. THE 시스템 SHALL 별도 Inbox 없이 버전 규칙으로 멱등하게 처리하고 처리 성공 후 Kafka 오프셋을 커밋한다. 실패하면 롤백하고 재전달받는다.

### R6. 시작 시 초기 동기화와 준비 상태
**사용자 스토리:** 운영자로서, Store가 시작하면 공개 상품 사본이 사람 개입 없이 채워지길 원한다. 그래야 배포·재시작이 단순하다.

#### 수용 기준
1. WHEN Store가 시작되면 THEN 시스템 SHALL 내부 키로 보호된 Catalog 전체 목록·상세를 조회해 초기 사본을 만든다.
2. THE 초기 동기화 SHALL Catalog `/internal/v1/store-products`·`/internal/v1/store-products/{code}`를 `CATALOG_STORE_SERVICE_KEY`로 조회하며, 내부 키는 이 두 GET에만 전달하고 공개 API로 우회하지 않는다.
3. WHILE HTTP 호출 중 THE 시스템 SHALL Store DB 트랜잭션을 열지 않는다.
4. WHEN 이벤트와 초기 동기화가 겹치면 THEN 시스템 SHALL catalogVersion 비교로 최신 사본을 유지한다.
5. WHEN 전체 초기 동기화가 한 번 성공하면 THEN 시스템 SHALL 메모리의 준비 상태를 연다. 빈 Catalog의 순회 완료도 정상이다.
6. WHILE 초기 동기화 전인 동안 THE 시스템 SHALL 업무 조회에 503을 반환한다.
7. IF 초기 동기화가 실패하면 THEN 시스템 SHALL 준비 상태를 열지 않고 자동으로 재시도한다. 원인 해소 뒤 재시작 없이 자동 재개한다.
8. WHEN Store를 재시작하면 THEN 시스템 SHALL 전체 대조를 처음부터 다시 시작하고 이미 저장한 사본은 보존한다.

### R7. 주기적 정본 대조와 자동 교정
**사용자 스토리:** 운영자로서, 정상 운영 중 생긴 누락·구버전·동일 버전 내용 차이가 자동으로 복구되길 원한다. 그래야 불가피한 예외에만 개입한다.

#### 수용 기준
1. WHILE 준비 완료 후 THE 시스템 SHALL 보호된 Catalog 정본을 주기적으로 대조해 누락·구버전 사본을 복구한다.
2. THE 기본 재시도 간격 SHALL 5초, 정상 대조 간격 SHALL 1분이며 설정으로 조정한다. 이는 운영 기본값이며 갱신 지연 SLA가 아니다.
3. WHEN 누락이 확인되면 THEN 시스템 SHALL INSERTED로, 정상적인 구버전 보완은 UPDATED로, 같은 catalogVersion의 내용 교정은 REPAIRED로 기록한다.
4. WHEN 같은 catalogVersion의 내용 차이가 확인되면 THEN 시스템 SHALL 직접 조회·검증한 Catalog 정본으로 사본을 교정한다(결정 67 A).
5. IF HTTP 응답이 늦는 동안 더 높은 버전이 이벤트로 반영됐다면 THEN 시스템 SHALL 행 잠금 뒤 확인한 최신 사본을 보존한다.
6. THE 시스템 SHALL 상품 ID·코드·최초 공개일의 식별 충돌이나 정본 자체의 계약 오류를 자동 덮어쓰지 않으며, 사본 삭제·강제 버전 하향·DLT 원본 재투입을 수행하지 않는다.
7. WHILE 정본 대조가 실패하는 동안 THE 시스템 SHALL 이미 준비된 사본의 조회를 계속 제공한다.
8. WHEN 전체 Catalog 접근 장애가 나면 THEN 시스템 SHALL 현재 대조를 중단하고 다음 재시도로 넘기되, 개별 상품의 계약·식별 오류는 기록하면서 나머지 상품 처리를 계속한다.

### R8. 자동 복구 감사와 관측
**사용자 스토리:** 운영자로서, 자동 처리의 진행·실패 근거를 확인하고 싶다. 그래야 사람이 개입할 예외를 판단한다.

#### 수용 기준
1. THE 시스템 SHALL `store_reconciliation`에 변경 전후 JSON·정본 조회 시각·기록 시각을 변경과 같은 트랜잭션으로 확정한다.
2. WHEN 같은 상태의 반복 대조가 일어나면 THEN 시스템 SHALL 새 교정 이력을 만들지 않는다.
3. THE 시스템 SHALL `stove.store.sync.ready`·`running`·`runs{result}`·`last.success.timestamp`·`last.scanned`·`last.changed`·`last.failed`·`last.skipped.newer` 지표를 등록하고, 상품 코드·실패 유형의 경고를 남긴다.
4. THE 지표 어댑터 SHALL 도메인 상태 공급자만 받고, 서비스·프레임워크 객체 조립은 config가 맡는다.

### R9. 최소 보안과 데이터 소유
**사용자 스토리:** 운영자로서, Store는 조회 경로만 열고 업무 쓰기 API를 두지 않길 원한다. 그래야 공개 조회 경계가 단순하다.

#### 수용 기준
1. THE Store 목록·상세·장르 조회 SHALL 익명 GET으로 허용하고, 새 업무 쓰기·운영 API를 만들지 않는다.
2. THE Store 보안 설정 SHALL 이 조회 경로와 필요한 health·API 문서 경로, 지표 수집 경로만 허용하고 나머지는 닫는다.
3. THE 시스템 SHALL 데이터 소유권을 구분하는 `stove_store` 스키마를 사용하고, 업무 테이블은 `store_product`·`store_product_genre`·`store_reconciliation`이다.
4. THE 시스템 SHALL 업무 이벤트를 발행하지 않고 별도 Inbox·Outbox·Elasticsearch·Redis 캐시·featured를 사용하지 않는다.

## 범위 밖(후속)

- Elasticsearch·Redis 캐시, 추천·인기 순위·개인화·형태소 검색, 대표 장르·장르 계층.
- 가격 범위·판매 상태 등 추가 검색 필터, featured·할인·쿠폰·구매 가능 여부 판정.
- 전역 단일 스캔·내구 커서/임대·다중 인스턴스 동기화 조율, 무중단 재색인, 대규모 성능 보장.
- 관리자 수동 재동기화 API를 정상 운영 경로로 두는 것(결정 66).
- 실제 경보 전송·대시보드/스크레이프 배포와 경보 임계값·허용 지연 선택 → 자동 복구 경보 연결 작업에서 닫음.
