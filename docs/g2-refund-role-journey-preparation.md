# G2 실제 역할별 수동 환불 여정 — 금융 실행 전 준비

**후속 실제 실행 완료:** 사용자가 이 실행안을 확인하고 `실행`을 지시해 [수동 환불·정지·다운로드 검증](g2-refund-role-journey-verification.md)을 완료했다. 전용 환경은 정리했고 PG는 CANCELLED·성공 취소 1건이다. 아래 미전송·허가 전·실행 중 환경 표현은 준비 시점의 기록이다. 같은 거래·명령을 다시 실행하지 않는다.

2026-10-30. [P2 금액 상세 구현](g2-refund-amount-details-implementation.md) 뒤 사용자의 `다음` 요청으로 실제 역할별 환불 여정을 준비했다. **실제 Auth·Gateway의 반려·재심사와 다운로드 유지까지 검증했고, 특정 TEST 거래의 관리자 승인·PG 취소는 아직 실행하지 않았다.** 새 구매 0건·PG 취소 요청 0건이다.

## 현재 검증 결과

| 단계 | 실제 결과 |
|---|---|
| 최신 실행본 | 검증된 소스 1,149개로 Auth·Gateway·Catalog·Order·Payment·License·Download bootJar를 OCI에서 빌드하고 일곱 앱 모두 UP 확인. 소스 해시와 실제 실행 JAR를 대조했다. |
| 자료 보존·격리 | 정상 구매 검증의 MySQL·Kafka·MinIO 자료를 새 경로에 복사했다. 복사 직전·직후와 역할 검증 후에도 원래 자료의 파일 해시가 같다. 기존 자료에 새 환불 행을 만들지 않았다. |
| 실제 역할 인증 | 원래 구매자·타인 MEMBER와 config bootstrap으로 만든 ADMIN이 Gateway 한 출처의 Authorization Code + PKCE S256·BFF callback·세션 쿠키로 로그인했다. 자체 서명 JWT나 업무 API에 직접 넣은 Bearer를 사용하지 않았다. ADMIN의 `system:bootstrap` 역할 감사도 확인했다. |
| 접근 제어 | 비로그인 환불 GET 401, 타인 환불 GET/POST 404, MEMBER 관리자 목록/판정 POST 403, 고객 접수·관리자 판정의 CSRF 누락 403, 타인 다운로드 ticket 403을 확인했다. |
| 자연 시각에 따른 수동 심사 | 원래 결제 확인 시각에서 약 11시간 53분이 지난 상태에서 최초 환불을 접수했다. 자동 자격이 아닌 PENDING_REVIEW로 진입했고 원래 주문·PG·서버 시각을 바꾸지 않았다. |
| 반려·재심사 | 실제 ADMIN의 최초 반려와 같은 명령의 반복 영수증, 고객의 재심사 접수와 같은 명령의 반복 영수증을 확인했다. 동일 refundId의 2차 심사가 PENDING_REVIEW다. 최초 판정자는 실제 ADMIN sub이며 고객 응답에 관리자 명령·판정자 정보가 노출되지 않는다. |
| 이용권·실제 파일 | 최초 심사 전·심사 대기·반려·재심사 대기 네 단계 모두 License ACTIVE, 본인 ticket/MinIO GET 200, no-store와 원본 ZIP 167바이트·SHA-256 일치를 확인했다. |
| 금액·실행 경계 | Order·Payment는 PAID, 원래 구매 금액 9,000원, Payment의 승인 실결제 8,976원·할인 24원이다. 환불은 1건이지만 License 정지 요청·Payment 환불 실행은 0건이다. 고객·관리자 금액 상세도 같다. |
| 과거 미확인 값 | 원래 Order 승인 이벤트는 P2 이전에 처리됐으므로 V27 이후 Order의 PG 승인 쌍은 NULL이다. 환불 대기 상세도 승인/반환을 추정하지 않는다. Payment가 가진 실제 승인 쌍과 구분하며, 취소 완료 이벤트 수신 후의 사본 보완은 남은 관측이다. |

원본 ZIP SHA-256은 `90e86dc0bc5963256d673f378b11c8756c22282e6063511803889ffa29fc701c`다. Download URL의 TTL은 전용 환경에서 2분으로 설정했다. 업무 시각을 조작하지 않으며 실제 취소 단계에서는 새로 발급한 기존 URL의 자연 만료와 정지 뒤 신규 발급 거절을 구분한다. 서명 URL은 수집 자료에 넣지 않았다.

Payment의 `PAYMENT_PROCESSING_ENABLED`·`PAYMENT_REFUND_EXECUTION_ENABLED`·`PAYMENT_REFUND_LOOKUP_ENABLED`·`PAYMENT_REFUND_RETRY_ENABLED`는 모두 false다. 준비 도구는 `/proc`의 해당 실행 프로세스 설정도 대조했다. 관리자 승인 명령은 파일로만 준비했으며 HTTP로 보내지 않았다.

## 다음 실제 금융 동작의 구체적인 범위

[기존 정상 구매](g2-purchase-download-verification.md)의 아래 TEST 거래 하나를 추천 대상으로 정리했다. 신규 결제 없이 이미 자연스럽게 수동 심사 자격에 들어온 구매로 관리자 승인·실제 정지·취소·다운로드 차단을 이어 볼 수 있다. 이 제안은 P1/P2의 정책을 다시 선택하는 질문이 아니다.

| 항목 | 대상·범위 |
|---|---|
| 주문 | `a76b2b26-7204-435a-abe1-8ed6c50222bf` |
| 결제 | `390a10d2-bac9-4b4f-8275-9f4b3813951f` |
| 상품 | `g2-journey-j4w1lzmm` |
| PG GET 확인 | PortOne V2 · TOSSPAY_V2 · TEST · PAID, 취소 총액 0원·취소 이력 0건. 준비 종료 시 재조회해 같은 상태·금액·승인 시각을 확인했다. |
| 원승인 | 2026-10-30 10:44:58 KST, 주문 총액 9,000원·PG 실결제 8,976원·할인 24원. 승인 실결제액을 현금 반환 완료액으로 간주하지 않는다. |
| 현재 환불 | `09da057c-9191-45a5-98bf-f53d55b8ac74`, PENDING_REVIEW |
| 승인할 회차 | 2차 `6eb78aae-3586-4bfd-8606-bb41d9e599a1` |
| 금융 동작 | 원결제의 전액 취소 **논리 거래 1건**. 신규 구매/승인 0건, 다른 결제 취소 0건. 기존 계약의 동일 키·원래 본문 재시도와 GET 결과 확인을 포함한다. HTTP 재송신 수와 금융 거래 수는 다르다. |
| 관리자 진입점 | 실제 ADMIN BFF 세션으로 `POST /api/v1/admin/refunds/{refundId}/reconsiderations/{reviewId}/decision`, 준비한 동일 requestId·APPROVED 본문. 아직 미전송이다. |
| 재개 직전 대조 | 대상 PG의 현재 PAID·TEST·미취소·원승인 구성, 같은 2차 미판정 회차·본인 구매·ACTIVE와 전용 작업자 설정을 다시 확인한다. 이미 취소되거나 회차가 바뀌었으면 명령을 반복하지 않고 현재 근거부터 확인한다. |
| 관측 | 실제 License 정지 선행, 고객/관리자 완료·취소 총액/직접 보고 포인트/NULL, 같은 명령·이벤트 수렴, Order·Payment의 원구매 PAID 보존, 새 ticket 거절, 정지 전 URL의 만료 전/자연 만료 후 결과를 대조한다. |

승인 뒤에는 대상 거래의 기존 전용 웹훅 경로를 이번 Payment에 연결할 필요도 함께 대조한다. 원래 공유 nginx 경로를 대체하지 않고 해당 검증 경로만 사용·복원한다. 준비 단계에서는 nginx나 실제 거래의 웹훅 설정을 변경하지 않았다.

실제 응답의 할인·포인트·취소 잔액이 현재 대조 계약과 다르면 금액을 추정해 강제로 완료하지 않는다. 새 취소 키·다른 결제·시각/DB 변경으로 성공을 만들지 않고 보존한 원결과와 GET으로 확인한다. 직접 근거가 없는 결제수단 반환액은 NULL이며 혜택 재사용은 제공사 정책이라는 [결정 35](decisions.md#provider-discount-full-refund)를 유지한다.

## 실행 환경·원자료와 대기 상태

- 로컬 준비 도구·수집 자료: Git 제외 `runs/g2-refund-journey-_itupull/`, 수집본은 `collected/`다. `runs/g2-refund-journey-current`는 이 경로를 적은 파일이다.
- OCI 격리 경로: `sng:/tmp/esd-g2-refund-journey-20261030.sdj1L1S4`. 원본 자료는 `sng:/tmp/esd-g2-journey-20261030.LeytgmUN`에 보존돼 있다.
- `prepare-result.json`·`prepare-api-audit.json`·네 단계 `download-*.json`은 역할별 HTTP·반려/재심사·파일 대조 근거다. `provider-before.json`·`provider-prepared.json`은 미취소 GET 근거다.
- `execution-plan.json`·`approval-proposal.json`·`proposed-approval-command.json`은 특정 거래의 미전송 실행안이다. `financialExecutionAuthorized=false`·`submitted=false`이며 파일 존재 자체를 실행 허가로 사용하지 않는다.
- `prepared-integrity.json`·`data-copy-integrity.json`·`original-data-after.json`·소스 manifest·빌드 결과는 원자료·실행본·자원 보존 근거다. Secret·암호·쿠키·서명 URL은 수집 묶음과 Git에서 제외했다.
- **현재 전용 컨테이너 4개와 앱 일곱 개는 금융 작업자를 끈 채 실행 중이다.** 기존 컨테이너 23개의 ID·시작 시각·재시작·health·mount와 볼륨 57개를 보존했고, 준비 후 전체 컨테이너는 27개·볼륨은 57개다. 모든 검증 포트는 loopback에 묶여 있다. 다음 세션은 이 소유 자원부터 확인하고 중복 환경을 만들지 않는다.

새 운영 코드·스키마·정책 변경은 없다. 이전 P2의 1,526건은 같은 소스의 선행 검증 근거이며 이번 여정에서 재실행하거나 역할별 HTTP 관측 수와 합산하지 않았다. 이번에는 일곱 앱 bootJar와 실제 준비 여정을 실행했다.

## 아직 완료하지 않은 것

이 문서는 **실제 수동 환불의 승인 전 준비 완료**다. PG 취소·완료 상세·신규 다운로드 차단·URL 자연 만료는 위 특정 금융 범위의 실행 뒤 확인한다. 자동 환불 새 TEST 구매, 실패/응답 유실의 HTTP 계약 픽스처와 실제 업무 정지/복구·명시적 재시도도 별도 남아 있다. 이번 반려·재심사 성공을 해당 결과나 G2 전체 완료로 확대하지 않는다. 지급 누락 복구·재구매 정책·G3 정산 조정은 [전체 계획](platform-generalization-plan.md)을 따른다.
