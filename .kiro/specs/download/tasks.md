# 작업: Download

> 상태: 작성 중(결정 150·178). 모듈을 넘는 작업은 [플랫폼 tasks](../platform/tasks.md)(T번호)에 있다. Download가 직접 걸린 플랫폼 T 항목은 없고, T27(런타임 의존 대조 — Download의 HTTP 두 경로가 대상)·T28(core 공급자 이름 규칙 — Download core는 지금 중립)·T30(옛 `LicenseIssued`·`LicenseRevoked` 제거)이 간접으로 닿는다. 경로 줄임 `download:` = `apps/download/src/main/java/com/stove/download/`, 줄 번호는 2026-11-09 `docs/spec-foundation` 기준.

## 완료 (검증 결과는 근거 색인)

- [x] 단일 ticket GET: `MEMBER` JWT `sub`로 본인 확인, 원래 사용자 토큰으로 License 상세 조회 — 2026-10-18
- [x] Catalog 내부 현재 공개 빌드 GET(`/internal/v1/products/{productCode}/build`), 서비스 키 보호·키 미설정 시 닫힘·Gateway/공개 OpenAPI 미노출 — 2026-10-18
- [x] `ReadBuildDownload`가 License·Catalog 상품 ID·코드 대조, 현재 `COMPLETED` 공개본만 사용 — 2026-10-18
- [x] 읽기 전용 저장소 HEAD(존재·크기)·presigned GET, 기본 TTL 5분(1초~15분), `expiresAt`=실제 서명 만료 — 2026-10-18
- [x] 응답 여섯 필드(`productCode`·`version`·`fileSize`·`checksum`·`downloadUrl`·`expiresAt`)·`no-store`, 토큰/키/URL 로그 미기록 — 2026-10-18
- [x] 오류 분기: 미보유/지급 전/`SUSPENDED` 403, 공개 상품 없음 404, 파일 누락/크기 불일치 409, 연계 장애 503 — 2026-10-18
- [x] URL 만료 전 반복 사용·공유 허용(일회용 아님), 발급 이력·보유권 소모 없음 — 2026-10-18
- [x] 무상태화: 업무 DB·MongoDB·Kafka 소비/발행·Inbox·Outbox·사본 제거, 외부 이벤트 없음 — 2026-10-18
- [x] 상품 다운로드 제한 반영: Catalog 내부 `403`·`PRODUCT_DOWNLOAD_RESTRICTED` 구분 403, 그 밖 내부 403·오류 503 — 2026-11-02 ([결정 50](../../../docs/decisions.md#independent-download-restriction))
- [x] 통합 검증: 실제 HTTP·JWT·MinIO 단위 93건·통합 8건, 실제 열 앱 15단계, 운영 JAR MongoDB/Kafka/Messaging/JPA 의존성 부재 — 2026-10-18 (제한 반영 관련 348건 — 2026-11-02, [근거 색인](../../../docs/evidence.md))
- [x] 업데이트·판매 중지 정책과 기존 보유자 접근의 전체 여정 검증 — 2026-11-08 G4 여정 `verifiesPublishedUpdateAndPermanentRight`·`enforcesIndependentSalesAndListingControls`·`restrictsHistoricalUrlsAndNewlyPublishedBuildIndependently` ([근거 색인](../../../docs/evidence.md) 2026-11-08 G4 행)
- [x] 새 구매 허용·기존 보유자 접근까지 포함한 Order·Download 경계의 운영 여정 — 2026-11-08 G4 여정 `restoresPublicAccessAndCurrentPurchaseSnapshot`(공개 복구 뒤 새 주문은 현재 가격·contentVersion, 미결제 구매자 403, 보유자 재구매 주문 허용·다운로드 유지) ([근거 색인](../../../docs/evidence.md) 2026-11-08 G4 행)

## 코드와 맞출 것

플랫폼 이월 목록(`module-carryover.md`)에 Download 절(MC-D*)은 없다 — 출시 초안 C절에서 Download 한 모듈 안의 차이가 나오지 않았다. 아래는 이번 재정의에서 새로 찾은 차이다. 보유권 영향이 큰 순서.

- [ ] **DL-T1. License·Catalog의 `404`를 본문 확인 없이 "열린 보유권 없음"·"공개 상품 없음"으로 바꾼다**
  - 무엇이: 어떤 `404`든(본문 없음·HTML·다른 오류 코드) License면 403, Catalog면 404로 끝난다. 잘못 설정된 base URL·경로 변경·앞단 프록시의 404가 고객에게 "보유하지 않은 상품"으로 보이고 장애가 가려진다.
  - 어디서: `download:adapter/LicenseHttpClient.java:23`, `download:adapter/CatalogBuildHttpClient.java:37-38`. 테스트가 지금 동작을 고정: `DownloadHttpClientsTest#distinguishesLibraryDenialsAndAvailability`(`404,FORBIDDEN`)·`#mapsCatalogStatuses`(`404,NOT_FOUND`, 본문은 성공 봉투 그대로).
  - 목표: R3.4·R3.9·R4.4·R4.7, 판정 표 J12·J13·J24·J25. `404`이고 본문이 `success=false`·`error.code=NOT_FOUND`일 때만 각 의미로, 그 밖은 `UPSTREAM_UNAVAILABLE`(503). 본문 해석은 `HttpResponseValues`에 의미별 메서드 하나로(J22의 403 해석과 같은 방식).
  - 영향: **보유권** — 보유자가 License 쪽 경로 문제로 "미보유" 안내를 받는다(잘못된 거절, 장애 경보는 `ServiceDown`에 안 잡힘). 돈 영향 없음.
  - 재현: `DownloadHttpClientsTest`에 `status=404, body="broken"`·`body=""`·`body={"success":false,"error":{"code":"OTHER"}}` → License·Catalog 모두 `UPSTREAM_UNAVAILABLE` 기대(지금 Red). 계약 본문 404는 지금 결과 유지.

- [ ] **DL-T2. 열린 보유권 없음과 정지가 같은 오류 코드 `FORBIDDEN`이다** — [결정 필요 M-DL-1] 결정 뒤
  - 어디서: `download:adapter/LicenseHttpClient.java:23`(404 → `ErrorCode.FORBIDDEN` "보유하지 않은 상품입니다."), `:31`(`SUSPENDED` → `ErrorCode.FORBIDDEN` "이용이 정지된 상품입니다."), `:25`(License 403 → `FORBIDDEN`).
  - 목표: R3.11 — M-DL-1의 선택에 따른 오류 코드(예: `DownloadError`에 사유별 상수).
  - 영향: **보유권 안내** — 클라이언트가 메시지 문자열로만 사유를 가른다(보관함 미등록 고객이 재구매로 갈 수 있다). 돈 영향은 재구매가 생길 때만(결정 89로 재구매 허용).
  - 재현: 통합 — License 404·`SUSPENDED`·Catalog 제한 세 경우의 `error.code`가 서로 다른지.

- [ ] **DL-T3. JWT issuer·JWK 주소에 로컬 기본값이 있어 설정 누락에도 기동한다**
  - 어디서: `apps/download/src/main/resources/application.yml:12-13`(`${JWT_ISSUER_URI:http://localhost:8091}` 등). 같은 패턴이 9개 모듈 `application.yml`에 있다(notes F-5).
  - 목표: R9.8 — 기본값 없이 필수. 누락이면 기동 거절(`DownloadConfigurationTest`와 같은 방식). 로컬 값은 compose·로컬 프로필이 넣는다.
  - 영향: 운영 설정 누락이 기동 성공 + 모든 요청 401로 나타난다(원칙 ⑦). 돈·보유권 직접 영향 없음.
  - 재현: `JWT_ISSUER_URI` 없이 컨텍스트 기동 → 실패 기대(지금 성공).

- [ ] **DL-T4. 서명 결과 검증 실패가 공통 처리기의 `log.error`로 가고, 예외 메시지에 서명 URL이 들어갈 수 있다**
  - 어디서: `download:core/domain/SignedUrl.java:10`(`URI.create(url)` — 형식 오류면 `IllegalArgumentException` 메시지에 원문 URL), `download:adapter/storage/S3BuildStorage.java:42-45`(이 예외를 잡지 않음), `common/web/.../GlobalExceptionHandler.java:82-86`(`log.error("unhandled exception", e)`).
  - 목표: R7.4, 판정 표 J35. 서명 결과 검증 실패를 URL 없는 업무 예외로 바꿔 500 `INTERNAL_ERROR`, 로그에 URL을 남기지 않는다.
  - 영향: 서명 URL(만료 전 누구나 받을 수 있는 자격)이 로그로 샐 수 있다. 서명기는 정상 URL만 만들어 발생 가능성은 낮다(코드상 추정).
  - 재현: `BuildStorage` 대역이 `"http://[bad"`를 돌려주게 하고 `OutputCaptureExtension`으로 로그에 그 문자열이 없는지.

- [ ] **DL-T5. 토큰·서비스 키·서명 URL이 로그에 남지 않는다는 확인 테스트가 없다(DL-I9)**
  - 어디서: 테스트 없음. `application.yml:51-52`는 `com.stove: DEBUG`.
  - 목표: R7.4. 통합 테스트 한 번의 전체 요청(성공·503·409) 동안 로그 출력에 토큰·키·`X-Amz-Signature` 문자열이 없음을 단언.
  - 영향: 회귀 감지 없음. 보유권 영향 없음.
  - 재현: `DownloadIntegrationTest`에 `OutputCaptureExtension` 추가.

- [ ] **DL-T6. 무상태(DL-I10)를 지키는 자동 검사가 없다**
  - 어디서: `apps/download/build.gradle`·`DownloadArchitectureTest`(공통 규칙만). 2026-10-18 "운영 JAR 의존성 부재"는 수동 확인 기록(근거 색인).
  - 목표: R9.1. ArchUnit 규칙 — `com.stove.download..`가 `jakarta.persistence..`·`org.springframework.kafka..`·`com.stove.common.messaging..`·`org.springframework.scheduling.annotation.Scheduled`를 쓰지 않음.
  - 영향: 사본·캐시가 다시 생겨도 CI가 모른다(원칙 ③).
  - 재현: 픽스처 클래스에 `@KafkaListener`를 둔 경우 규칙이 실패하는지.

- [ ] **DL-T7. 쓰지 않는 설정 키와 어댑터 안의 객체 생성**
  - 어디서: `application.yml:21-23`(`stove.messaging.ops.enabled` — messaging 의존 없음), `download:adapter/CatalogBuildHttpClient.java:31`(요청마다 `new ObjectMapper()`, AGENTS.md 3절 "객체 조립은 config").
  - 목표: 키 삭제, `ObjectMapper`는 `config`에서 주입.
  - 영향: 없음(정리).
  - 재현: 해당 없음 — 컴파일·기존 테스트.

- [ ] **DL-T8. 저장소 장애·자격 거절(J31·J33)의 확인 테스트가 없다**
  - 어디서: `DownloadIntegrationTest`는 객체 없음·크기 불일치·잘못된 참조만 본다(`:284-292`).
  - 목표: R6.9. 저장소 컨테이너 정지·틀린 secret으로 503, URL 미발급.
  - 영향: 장애가 409·500으로 바뀌는 회귀를 모른다.
  - 재현: 통합 — 틀린 secret의 `S3BuildStorage`로 `download` 호출 → `UPSTREAM_UNAVAILABLE`.

## 진행 중 / 남음

- [ ] [결정 필요 M-DL-1] `403` 거절 사유(열린 보유권 없음·보관함 미등록·정지·다운로드 제한)를 고객에게 구분해 알리는 계약 — 옛 항목 "결제/지급 대기·정지·파일 없음을 구분해 전달하는 계약(G4)"을 이 항목으로 옮김. "파일 없음"은 이미 409로 구분된다. 결정 뒤 DL-T2.
- [ ] [결정 필요 M-DL-2] License가 사용자 토큰에 `401`·`403`을 줄 때 그대로 전달할지, 연동 장애(503)로 볼지.
- [x] ~~[결정 필요] 공개 노출 중지와 기존 구매자 다운로드 금지를 분리한 정책의 일관된 안내·거절 — G4~~ → 정책은 결정 48·50·51·52가 이미 정했다(판매·노출은 다운로드 판단에 쓰지 않음, 다운로드 제한은 독립, R5.4). G4 여정 `enforcesIndependentSalesAndListingControls`로 확인됨(2026-11-08). 남은 "안내" 부분은 M-DL-1로 합침.
- [ ] [외부 대기] 다운로드 제한의 공유 환경 적용: 읽기 계정 IAM 태그 거절 정책·Catalog 태그 계정 준비와 배포(격리 검증만 완료) — 미수행
- [ ] [외부 대기] 공유 배포 환경의 Catalog 내부 키·Download 읽기 전용 계정 준비와 기존 인스턴스 교체, 구 MongoDB 자료·소비 그룹 오프셋 정리(현재 보존·미사용) — 미수행

## 범위 밖(후속)

- 델타 패치·다중 플랫폼·OS/아키텍처별 빌드·CDN/DRM 최적화.
- 다운로드 이력·횟수 제한·완료 이벤트·재개 위치·운영자 발급 API.
- 일회용 URL·공유 차단·사용자별 CDN 토큰·자체 파일 중계.
- 환불·회수·기간제·무료 지급·선물·정산 연계.
- License·Catalog 장애 중 계속 발급하기 위한 사본·동기화·다중 인스턴스 조율.
