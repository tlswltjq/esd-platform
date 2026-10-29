# 전액 환불 현재 인계

2026-10-29 기준. 다음 세션은 [공통 지시·다음 작업](refund-next-session.md)부터 읽는다. 상세 정책·단계별 검증은 [PG 테스트 연동·환불 기록](pg-test-integration-plan.md), 이전 대화의 진행·실패·대기 기록은 [과거 세션 이력](history/refund-session-through-2026-10-28.md)에 보존했다. 이 문서는 최신 상태의 요약이며 실제 코드·Git·프로세스 상태를 함께 확인한다.

기존 누적 환불 구현은 `67f250d`, 문서 정리는 `6b010d5`, 고객 명시적 재시도 HTTP는 `fe3bd49`에 커밋했다. Payment 운영 중단 조회·재개 관리자 API, 반려 뒤 고객 재심사와 잘못된 승인 중단·새 심사 복귀는 `3f51314`에 함께 커밋했다. 고객 지연 안내·Payment→Order 준비 이벤트 보관은 `de1ead0`에, 운영 조회·조사 기록은 `7f96c3a`에 커밋했다. 이후 변경과 원격 동기화 여부는 `git log`·`git status`로 확인한다.

## 현재 완료한 범위

- 고객 MEMBER의 전액 환불 접수/조회, 첫 접수 시각과 4시간 자격 보존, 중복 단일 접수.
- 관리자 ADMIN의 심사 목록/상세/승인/반려. **먼저 커밋된 판정 확정 A안**. 같은 명령·환불·판정자·판정·사유만 최초 영수증으로 재전송 처리하며 다른 지시는 409다. 반려는 REJECTED와 고객에게 보이는 사유로 구분한다.
- 승인 또는 자동 허용 → 실제 지급 확인 → 정지 요청 → 실제 이용권 정지 확인 → Payment 환불 실행 요청. 관리자 API가 PG를 직접 호출하지 않는다.
- Payment의 PortOne 취소/조회, 같은 attemptId·고정 본문·최초 준비 3시간 창, 내구 조회/재송신 일정, 오류별 자동 복구/운영 중단 정책.
- 전액 취소 증거에 따른 환불 완료와 실행 성공 귀속 구분. 확인된 실패 → 복구 요청 → 실제 이용권 복구 → FAILED.
- 확정 실패·실제 복구 후 고객 명시적 재시도 HTTP를 구현·검증했다. 고객 조회의 retryableAttemptId를 POST `/api/v1/orders/{orderNo}/refund/retries`의 previousAttemptId로 전달한다. 같은 ID의 재전송은 최초 접수 영수증을 반환한다.
- Payment 운영 중단의 ADMIN 목록·상세·재개 API를 구현·검증했다. `/api/v1/admin/refund-cancellation-holds`에서 사유·기한·재개 가능 여부와 이전 지시를 조회하고 같은 중단/시도의 송신을 재예약한다. ADMIN sub·사유와 최초 영수증을 저장하며 원래 키·본문·3시간 창을 유지한다. 실제 계정·운영 화면·실제 PG 장애 뒤 재개 검증은 후속이다.
- 반려 뒤 고객 직접 재심사를 구현·검증했다. 횟수 제한 없이 마지막 반려 저장 후 7일 경계까지 필수 사유로 접수하고, 별도 회차의 관리자 판정·고객/관리자 이력을 제공한다. 과거 접수·판정 영수증은 이후 회차를 바꾸지 않고 최초 환불 자격·구매 사실·과거 이력을 보존한다. 승인 뒤 기존 지급→실제 정지→PG 실행·확정 실패/복구/고객 재시도 계약을 유지한다.
- 잘못된 승인 중단과 새 심사 복귀를 구현·검증했다. Order의 Payment 실행 요청 저장 전까지만 ADMIN 중단을 받고 과거 승인/중단을 보존한다. 정지 요청 전에는 바로, 정지 요청 뒤에는 실제 정지·해당 정지의 복구 완료 뒤 새 관리자 정정 회차로 복귀한다. 오래된 명령/이벤트는 이후 회차나 새 정지를 바꾸지 않는다.
- 장기 미확정의 고객 지연 안내와 Payment→Order 준비 이벤트 보관을 구현·검증했다. 최초 준비 + 3시간 경계부터 현재 미확정 시도에만 processingNotice를 제공하고 기존 PROCESSING을 유지한다. 완료/실패·복구·새 시도와 과거 이벤트를 구분하며 준비 근거가 없으면 시각을 추정하지 않는다.
- 장기 미확정 운영 A안의 ADMIN 목록·상세·조사 이력/기록을 구현·검증했다. 원래 준비의 3시간 경계와 현재 금융 미확정으로 조회하며 JWT sub·내용·서버 시각을 별도 저장한다. 조사 기록은 금융 상태·작업 일정을 바꾸지 않고 결과 확정 뒤에도 보존한다.
- 실제 새 9,000원 TEST 주문에서 자동 환불 전체 흐름 1건을 완료했다. 실제 관리자 수동 심사 PG 환불이나 Settlement 환불 조정의 완료는 아니다.

## 일반화 G1의 첫 범위: 같은 주문 재결제 A안

PortOne의 개별 실패 시도는 원구매·금액과 대조한 별도 이력이며 Payment/Order의 최종 실패가 아니다. 기존 PENDING checkout의 같은 orderNo·paymentId·금액으로 다시 결제한다. MEMBER `/api/v1/payments/{orderNo}/attempt-failures`는 본인 페이지와 수집 상태를 DB에서 no-store로 제공한다. PAID·결과 Outbox를 먼저 커밋하고 이력은 별도 HTTP/DB로 수집·재개한다. 오래된 PENDING 응답은 승인 뒤 예약을 지우지 않는다. V28과 최종 OCI 640건의 [검증 기록](pg-test-integration-plan.md#2026-10-29-같은-주문-재결제와-실패-시도-이력-a안)을 따른다. 실제 TEST 재결제와 G1 전체 완료는 구분한다.

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
| 동시 판정 | Order 잠금 뒤 최신 Refund·회차를 읽음. 각 회차의 최초 판정·감사·필요 Outbox를 원자 저장. 반려 뒤에는 고객 재심사 회차를 사용하고, 잘못된 승인은 선택한 실행 요청 저장 전 중단·필요 복구 뒤 관리자 정정 회차를 사용. 과거 판정을 덮어쓰지 않음 |

## 검증과 근거

| 검증 | 결과·출처 |
|---|---|
| 같은 주문 재결제 A안 포함 최신 Payment | 단위 314·통합 209, 실패/오류/건너뜀 0. `runs/payment-attempts-oci-fdk5ugoo/collected/results/corrected` |
| 같은 주문 재결제 A안 포함 최신 Gateway | 단위 116·Redis 통합 1, 실패/오류/건너뜀 0. 같은 corrected 입력 |
| 같은 주문 재결제 OCI 보존 | 공유 컨테이너 23개·볼륨 57개와 상태 보존, 추가/잔여 자원 없음. `runs/payment-attempts-oci-fdk5ugoo/validation-summary.json` |
| 지연 안내 포함 최신 Order | 단위 331·통합 170, 실패/오류/건너뜀 0. `runs/refund-delay-notice-oci-fwbz8qjm/collected/results/corrected` |
| 이전 준비 이벤트 Payment | 단위 292·통합 183, 실패/오류/건너뜀 0. 같은 corrected 입력 |
| 준비 이벤트 포함 최신 공통 Kafka | 단위 217, 실패/오류/건너뜀 0. 같은 corrected 입력 |
| 지연 안내 OCI 보존 | 공유 컨테이너 23개·볼륨 57개와 상태 보존, 추가/잔여 자원 없음. `runs/refund-delay-notice-oci-fwbz8qjm/validation-summary.json` |
| 이전 승인 중단 Order | 단위 323·통합 158, 실패/오류/건너뜀 0. `runs/refund-approval-stop-oci-zbcavxkv/collected/results/full` |
| 승인 중단 포함 최신 License | 단위 102·통합 59, 실패/오류/건너뜀 0. 같은 full 입력 |
| 이전 승인 중단 Gateway | 단위 111·Redis 통합 1, 실패/오류/건너뜀 0. 같은 full 입력 |
| 이전 승인 중단 공통 Kafka | 단위 213, 실패/오류/건너뜀 0. 새 정정 요청/복구 이벤트의 전체 계약 포함 |
| 승인 중단 OCI 보존 | 공유 컨테이너 23개·볼륨 57개와 상태 보존, 추가/잔여 자원 없음. `runs/refund-approval-stop-oci-zbcavxkv/validation-summary.json` |
| 이전 재심사 Order | 단위 315·통합 142, 실패/오류/건너뜀 0. `runs/refund-reconsideration-oci-2fz1w7px/collected/results/corrected` |
| 이전 재심사 Gateway | 단위 110·Redis 통합 1, 실패/오류/건너뜀 0. 같은 corrected 입력. 기존 Payment 관리자 경로도 회귀 검증 |
| 재심사 OCI 보존 | 기존 컨테이너 23개·볼륨 57개 및 ID·시작/재시작·상태 보존. 추가/잔여 컨테이너·볼륨 없음. `runs/refund-reconsideration-oci-2fz1w7px/validation-summary.json` |
| 이전 운영 중단 관리자 Payment | 단위 290·통합 177, 실패/오류/건너뜀 0. `runs/refund-resume-admin-oci-o2_0_er9/collected/results/full` |
| 이전 운영 중단 관리자 Gateway | 단위 106·Redis 통합 1, 실패/오류/건너뜀 0. 같은 full 입력. ADMIN 권한·경로/쿼리/본문/Bearer·Location 전달·위조 회원 헤더 제거 |
| 운영 중단 관리자 OCI 보존 | 기존 컨테이너 23개·볼륨 57개 및 상태 보존, 추가/잔여 자원 없음. `runs/refund-resume-admin-oci-o2_0_er9/validation-summary.json` |
| 이전 고객 명시적 재시도 Order | 단위 308·통합 124, 실패/오류/건너뜀 0. `runs/refund-retry-http-oci-fw4f42_q/collected/results/full` |
| 이전 재시도 경로 Gateway | 단위 100·Redis 통합 1, 실패/오류/건너뜀 0. 같은 full 입력. Bearer·본문·경로·응답·Location 전달과 위조 회원 헤더 제거 |
| 고객 재시도 OCI 보존 | 기존 컨테이너 23개·볼륨 57개 및 ID·시작/재시작·상태 보존, 추가/잔여 자원 없음. `runs/refund-retry-http-oci-fw4f42_q/validation-summary.json` |
| Order 관리자 심사 포함 | 단위 290·통합 114, 실패/오류/건너뜀 0. `runs/refund-review-oci-d1jx_po3/collected/results/corrected` |
| 이전 Payment INVALID_REQUEST 중단 | 단위 276·통합 166, 실패/오류/건너뜀 0. `runs/refund-invalid400-oci-3bpzduhu/collected/results/corrected`. 당시 코드·이력은 새 관리자 API 검증과 구분 |
| 이전 공통 Kafka 계약 | 단위 205, 실패/오류/건너뜀 0. `runs/refund-retry-oci-q_c7wgvb/collected/results/full`. 이후 승인 정정 포함 213건·준비 이벤트 포함 최신 217건은 위 기록을 따름 |
| Gateway 관리자 경로 | 단위 97·Redis 통합 1, 실패/오류/건너뜀 0. `runs/refund-review-gateway-oci-7l32q55b/collected/results/full`. ADMIN 전달·다른 역할/경로 차단·Bearer 전달/위조 회원 헤더 제거 |
| TDD | 관리자 판정 도메인 Red 6건·Gateway Red 4건. `runs/refund-review-tdd-brt1d7x3` |
| 이전 관리자 API 명세 | 기존 주문/고객 API 3개 경로·5개 연산 보존. 고객 REJECTED/rejectionReason 및 관리자 3개 연산 추가 |
| OCI 보존 | 기존 컨테이너 23개·볼륨 57개, ID·시작/재시작·상태 보존. 실제 비밀값 소스 묶음 포함 0 |
| 실제 자동 환불 | `runs/refund-live-obe63z8s/e2e-result.json`의 complete=true. 이전 고객 URL 자체의 만료 후 GET은 미확인이고 별도 S3 만료 제어 검사와 구분 |

최초 OCI의 명세 차이와 JDBC CHECK 위반 예외 분류 테스트를 보정하고 전체를 정상 명세 검사로 다시 실행했다. `schema-update`에 남은 단위 XML은 재실행 수치가 아니다. 상세 근거는 [관리자 A안 기록](pg-test-integration-plan.md#2026-10-28-관리자-최초-환불-판정-확정-a안)을 따른다.

고객 재시도 HTTP의 Red 8건, 초기 Gateway CSRF 기대값·OCI 중복 구매 픽스처 오류와 최종 전체 재실행은 [고객 재시도 HTTP 기록](pg-test-integration-plan.md#2026-10-28-고객-명시적-재시도-http-첫-번째-안)을 따른다. 기존 6개 경로·8개 연산을 유지하고 1개 연산과 nullable retryableAttemptId를 추가했다. 새 HTTP 통합 10건의 Payment 실패·License 복구 완료는 계약 픽스처이며 실제 PG·License 전체 연계 완료를 뜻하지 않는다.

운영 중단 관리자 API는 Gateway 경로 Red 3건과 명령 유일 키 경합 Red 1건 뒤 최종 전체 574건을 통과했다. spy 추상 메서드 호출과 잔액 중단 필드 기대값의 픽스처 오류는 업무 Red에서 제외했다. 기존 Payment 2개 조회 연산은 그대로 두고 관리자 3개 연산을 추가했다. [관리자 API 상세 기록](pg-test-integration-plan.md#2026-10-28-운영-중단-조회재개-관리자-api-a안)을 따른다.

재심사는 HTTP Red 2건·Gateway Red 1건 뒤 구현했다. 본문 전체 대조의 JSON 숫자 노드 타입 오류 1건을 정정한 최종 corrected 전체 568건이 통과했다. V21의 최초 이력 복사·과거 판정 미조작, 18회차·기한 경계/잠금 지연·기한 뒤 정상 판정·명령 경합·실제 flush 롤백·승인/정지/확정 실패/복구/기존 고객 재시도를 검증했다. Payment 소스 193개는 앞선 검증과 같고 재실행하지 않았다. [재심사 상세](pg-test-integration-plan.md#2026-10-29-반려-뒤-고객-재심사)를 따른다.

승인 중단은 서비스 Red 3건 뒤 구현했으며 최종 OCI 전체 967건을 통과했다. 테스트 설정/열 이름/CHECK 예외 분류 보정과 각 모듈 실제 DB·Kafka 검증의 범위는 [승인 중단 검증 기록](pg-test-integration-plan.md#2026-10-29-잘못된-승인-중단과-새-심사-복귀)을 따른다. 기존 최초 판정과 고객 반려 뒤 재심사, 실제 정지 선행·확정 실패 복구·고객 명시적 재시도 계약을 유지했다.

지연 안내는 준비 발행·원래 경계와 Order의 저장 조회를 연결했다. 최종 OCI 전체 1193건의 실제 DB/Kafka·마이그레이션·명세와 Gateway/License 미재실행 범위는 [지연 안내 검증 기록](pg-test-integration-plan.md#2026-10-29-장기-미확정-고객-안내와-준비-이벤트)을 따른다. 기존 운영 중단·금융 결과·복구/재시도 정책을 보존하며 장기 미확정을 실패나 새 시도로 바꾸지 않는다.

운영 조회·조사 기록은 최종 OCI 전체 615건을 통과했다. 처음 V26→V27 데이터 복사의 자동 생성 열 오류와 테스트 정정·실제 DB 경합/flush 롤백·Gateway/명세·미재실행 모듈 범위는 [운영 조회·조사 기록](pg-test-integration-plan.md#2026-10-29-장기-미확정-운영-조회와-조사-기록-a안)을 따른다. 조사 기록 저장은 금융 결과 판정이나 일정 변경이 아니다.

## 유지 중인 실제 검증 환경

- 고객 재시도·운영 중단 관리자 API·재심사·승인 중단·지연 안내·운영 조회/조사 기록과 같은 주문 재결제 작업에서는 기존 실제 검증 런타임·비밀 설정·거래를 갱신하지 않았다. 아래 기존 Order V20 런타임에는 재심사 V21·승인 중단 V22·준비 정보 V23을 적용하지 않았다. 실제 Payment V25 DB에는 준비 사실 V26·조사 기록 V27·결제 실패 이력 V28을, 실제 License DB에는 승인 정정 V9를 적용하지 않았다.
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

2026-10-29 사용자는 전 기능의 일반적인 사용 수준 완성 → 엣지 케이스·성능 고도화를 현재 목표로 정했다. [전체 기능 일반화 계획](platform-generalization-plan.md)의 G1 첫 범위인 같은 주문 재결제 A안은 코드·계약 검증을 마쳤다. 다음 권장 작업은 미결제 이탈/재진입·안전한 종료 경계다. 환불의 기존 선택과 운영 조회·조사 기록, 같은 주문 재결제 A안은 다시 묻지 않는다. 조사 종결 등의 [개별 환불 백로그](refund-next-session.md#추천하는-다음-논의-조사-이후-조치와-종결-기준)는 보존하며 자동으로 최우선 작업으로 삼지 않는다. 실제 ADMIN/PG 전체 여정·Settlement 환불 조정 등은 전체 계획에서 순서를 정해 진행한다.
