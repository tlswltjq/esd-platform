# 작업: Download

## 완료 (MVP·검증 기록 있음)

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

## 진행 중 / 남음 (일반화 공백 — G4)

- [ ] [결정 필요] 결제/지급 대기·정지·파일 없음을 사용자에게 구분해 전달하는 계약 — G4
- [ ] [결정 필요] 공개 노출 중지와 기존 구매자 다운로드 금지를 분리한 정책의 일관된 안내·거절 — G4
- [ ] [외부 대기] 다운로드 제한의 공유 환경 적용: 읽기 계정 IAM 태그 거절 정책·Catalog 태그 계정 준비와 배포(격리 검증만 완료) — 미수행
- [ ] [외부 대기] 공유 배포 환경의 Catalog 내부 키·Download 읽기 전용 계정 준비와 기존 인스턴스 교체, 구 MongoDB 자료·소비 그룹 오프셋 정리(현재 보존·미사용) — 미수행

## 범위 밖(후속)

- 델타 패치·다중 플랫폼·OS/아키텍처별 빌드·CDN/DRM 최적화.
- 다운로드 이력·횟수 제한·완료 이벤트·재개 위치·운영자 발급 API.
- 일회용 URL·공유 차단·사용자별 CDN 토큰·자체 파일 중계.
- 환불·회수·기간제·무료 지급·선물·정산 연계.
- License·Catalog 장애 중 계속 발급하기 위한 사본·동기화·다중 인스턴스 조율.
