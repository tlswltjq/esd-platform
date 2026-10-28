# 전액 환불 현재 인계

2026-10-28 기준. 다음 세션은 [공통 지시·다음 작업](refund-next-session.md)부터 읽는다. 상세 정책·단계별 검증은 [PG 테스트 연동·환불 기록](pg-test-integration-plan.md), 이전 대화의 진행·실패·대기 기록은 [과거 세션 이력](history/refund-session-through-2026-10-28.md)에 보존했다. 이 문서는 최신 상태의 요약이며 실제 코드·Git·프로세스 상태를 함께 확인한다.

현재 누적 환불 구현은 `67f250d`에 커밋했다. 문서 정리 커밋과 원격 동기화 여부는 `git log`·`git status`로 확인한다.

## 현재 완료한 범위

- 고객 MEMBER의 전액 환불 접수/조회, 첫 접수 시각과 4시간 자격 보존, 중복 단일 접수.
- 관리자 ADMIN의 심사 목록/상세/승인/반려. **먼저 커밋된 판정 확정 A안**. 같은 명령·환불·판정자·판정·사유만 최초 영수증으로 재전송 처리하며 다른 지시는 409다. 반려는 REJECTED와 고객에게 보이는 사유로 구분한다.
- 승인 또는 자동 허용 → 실제 지급 확인 → 정지 요청 → 실제 이용권 정지 확인 → Payment 환불 실행 요청. 관리자 API가 PG를 직접 호출하지 않는다.
- Payment의 PortOne 취소/조회, 같은 attemptId·고정 본문·최초 준비 3시간 창, 내구 조회/재송신 일정, 오류별 자동 복구/운영 중단 정책.
- 전액 취소 증거에 따른 환불 완료와 실행 성공 귀속 구분. 확인된 실패 → 복구 요청 → 실제 이용권 복구 → FAILED.
- 확정 실패·실제 복구 후 고객의 명시적 새 시도는 내부 유스케이스까지 구현했다. 고객 재시도 HTTP와 Payment 운영 중단 재개 HTTP는 아직 없다.
- 실제 새 9,000원 TEST 주문에서 자동 환불 전체 흐름 1건을 완료했다. 실제 관리자 수동 심사 PG 환불이나 Settlement 환불 조정의 완료는 아니다.

## 유지할 계약

| 항목 | 계약 |
|---|---|
| 소유권 | Order는 접수·심사·진행, Payment는 PG 실행/조회, License는 실제 이용권 상태를 소유 |
| 원구매 | PAID·원래 금액·상품·구매 근거를 환불 상태로 덮어쓰지 않음 |
| 자동 허용 | 최초 Payment 결과 시각부터 정확히 4시간 이내 접수. 처리 지연/재시도로 첫 자격을 초기화하지 않음 |
| 정지·접근 | 심사 중에는 권한 유지. 실제 정지 뒤에만 PG 실행. SUSPENDED 상품은 라이브러리에 남고 신규 Download는 403 |
| 기존 URL·파일 | 발급된 URL은 만료까지 사용 가능. 이미 받은 파일 회수·즉시 URL 무효화는 범위 밖 |
| PG 중복 방지 | attemptId를 PG Idempotency-Key로 사용. 같은 실행은 같은 키/UTF-8 본문·금액/currentCancellableAmount. 부분 취소액을 채워 넣지 않음 |
| 첫 송신·일정 | Outbox/Kafka 소비자가 접수 커밋 뒤 첫 POST. 같은 시도 재송신은 10/30/60초, GET은 5/15/35초 이후 60초. 내구 예약·소유권/lease 사용 |
| 미확정 | 시간 경과·타임아웃·연결 실패만으로 실패, 복구, 새 키/시도를 만들지 않음. 정지와 GET을 유지 |
| 인증·조회 오류 | 401·GET 403/404·POST 404는 기존 조건 회복 후 같은 요청 재개. 다른 구매·부분 취소·완료·기한·기존 운영 중단을 우회하지 않음 |
| 취소 요청 거절 | POST 403 및 정확한 400 INVALID_REQUEST는 자동 POST 중단·GET 유지. 운영자의 명시적 내부 재개도 원래 키/본문/3시간을 유지 |
| 성공·실패 근거 | 직접 SUCCEEDED 또는 검증된 전액 취소 조회를 대조. FAILED는 자기 취소 실패 영수증과 원결제 전액 미취소 조회 등 추가 증거 필요 |
| 동시 판정 | Order 잠금 뒤 최신 Refund를 읽음. 판정·감사 기록·필요 Outbox 원자 저장. 판정 번복/반려 재심사는 별도 미결 |

## 검증과 근거

| 검증 | 결과·출처 |
|---|---|
| Order 관리자 심사 포함 | 단위 290·통합 114, 실패/오류/건너뜀 0. `runs/refund-review-oci-d1jx_po3/collected/results/corrected` |
| Payment 마지막 코드 | 단위 276·통합 166, 실패/오류/건너뜀 0. `runs/refund-invalid400-oci-3bpzduhu/collected/results/corrected`와 현재 Payment 소스 동일 |
| 공통 Kafka 계약 | 단위 205, 실패/오류/건너뜀 0. `runs/refund-retry-oci-q_c7wgvb/collected/results/full`과 현재 소스 동일 |
| Gateway 관리자 경로 | 단위 97·Redis 통합 1, 실패/오류/건너뜀 0. `runs/refund-review-gateway-oci-7l32q55b/collected/results/full`. ADMIN 전달·다른 역할/경로 차단·Bearer 전달/위조 회원 헤더 제거 |
| TDD | 관리자 판정 도메인 Red 6건·Gateway Red 4건. `runs/refund-review-tdd-brt1d7x3` |
| 명세 | 기존 주문/고객 API 3개 경로·5개 연산 보존. 고객 REJECTED/rejectionReason 및 관리자 3개 연산 추가 |
| OCI 보존 | 기존 컨테이너 23개·볼륨 57개, ID·시작/재시작·상태 보존. 실제 비밀값 소스 묶음 포함 0 |
| 실제 자동 환불 | `runs/refund-live-obe63z8s/e2e-result.json`의 complete=true. 이전 고객 URL 자체의 만료 후 GET은 미확인이고 별도 S3 만료 제어 검사와 구분 |

최초 OCI의 명세 차이와 JDBC CHECK 위반 예외 분류 테스트를 보정하고 전체를 정상 명세 검사로 다시 실행했다. `schema-update`에 남은 단위 XML은 재실행 수치가 아니다. 상세 근거는 [관리자 A안 기록](pg-test-integration-plan.md#2026-10-28-관리자-최초-환불-판정-확정-a안)을 따른다.

## 유지 중인 실제 검증 환경

- 기존 실행: `runs/portone-order-live-j2flh033`, 실제 새 환불: `runs/refund-live-obe63z8s`. `runs/*-current` 파일에서 경로를 확인한다.
- 로컬 Auth 18891, Catalog 18881, Order 18882, Payment 18783, License 18884, Download 18888. OCI의 전용 MySQL 13384·Kafka 19492·MinIO 19008을 SSH로 연결한다. PID는 해당 run의 파일을 다시 확인한다.
- Order V20 runtime PID **33244**, JAR SHA-256 `317c5978346e093e7949e737569ff56fdb08d007f10633f7ac95ebff59b2c2fa`. Payment V25 runtime PID **22084**, SHA-256 `718a4baa937f93e31ae7e651a5032a01e48ba9ebb74a91ccc64edad6a9cf7475`.
- Gateway 코드의 관리자 경로/권한과 HTTP 전달은 검증했다. 아래 실제 주문 스택은 서비스 직접 연결이며 Gateway 프로세스를 새로 기동하지 않았다.
- 실제 MEMBER의 관리자 목록/상세는 403이다. 실제 ADMIN 계정을 새로 만들거나 권한을 부여하지 않았다.
- 공개 웹훅은 `https://161.33.31.159/api/v1/payments/webhooks/portone`. IP 인증서 갱신/전용 edge/로컬 Payment 터널 기반이며 상시 운영 배포가 아니다.
- 비밀값은 `.env.portone-test` 및 run의 private.json에만 있다. 실제 설정 해시는 Order 적용 전후 같으며 다른 앱·SSH PID도 유지했다.

| 보존할 실제 거래 | 상태 |
|---|---|
| 보호 주문 `b3b36f62-625f-44d9-a444-23450b97e3c6`, 결제 `ce01f6e7-f3f5-4934-a169-a64e9983ab94` | 원결제/PG PAID, 환불 GET 404. 임의 접수·승인·취소 금지 |
| 완료 주문 `b1898f83-e679-4c32-9590-452e35a53742`, 결제 `536805b3-2d66-4b0e-98fd-458e12b11103` | 원 PAID 보존, PG CANCELLED/9,000원 취소 1건, 환불 COMPLETED |
| 완료 환불 `e0e11b4c-9363-4e6a-a92f-e290e1cc7f05`, 시도 `8688b733-f60a-403f-9203-4634945b3ea9` | 실행 SUCCEEDED/PG_RESPONSE, Library SUSPENDED, 신규 Download 403, 관련 Outbox 8건 SENT |

휴대폰 승인은 끝났고 토스페이 TEST 마지막 “이동하기”까지 확인했다. 결제/환불 스크립트를 재실행하지 않는다. 초기 DB 보고서 오류는 읽기 전용 검증으로 복구했으며 원자료에 남겼다. 브라우저 진단 포트 9229는 닫았고 결제 headless 브라우저는 종료했다.

다음 작업과 미결 정책은 [공통 지시·다음 작업](refund-next-session.md#다음-작업-고객의-명시적-재시도-http-계약-연결)에 있다. 사용자에게 제시하고 선택을 받은 뒤 진행한다.
