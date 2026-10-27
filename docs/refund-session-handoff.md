# 전액 환불 학습·구현 세션 인계

2026-10-27 작성. 기준 저장소는 `/Users/jade/Projects/esd-platform`, 브랜치는 `main`, 초기 인계 기준 HEAD는 `ff9a6d90b339bad9e1e872457fd4a05c7354b59f`이며 후속 작업은 아래 최신 기록과 git log를 대조한다. 아래 내용은 이 시점의 합의·구현·검증을 구분한 기록이다. 이후 변경이 있으면 실제 코드와 최신 결정 기록을 먼저 대조한다.

## 1. 사용자의 목표와 협업 방식

사용자는 전액 환불을 직접 설계하며 배우고, 이력서에 설명할 설계·구현 역량의 사례를 만들고 있다. 정책을 대신 결정하지 말고 다음 방식을 따른다.

1. 다음 목표와 필요한 이유를 설명한다. 한 번에 결정할 핵심 쟁점 하나를 제시한다.
2. 배경·문제·선택지·비용·트레이드오프와 Codex의 의견을 설명한다. 기술뿐 아니라 고객·사업 관점도 다룬다.
3. 사용자가 선택하고 이유를 설명할 시간을 준다. 미결 정책을 테스트 기대값이나 코드로 먼저 확정하지 않는다.
4. 선택의 반례·놓친 비용을 검토하고 합의 내용을 기록한다. 사용자가 이미 확정한 내용을 매번 다시 묻지 않는다.
5. 합의 후 실제 테스트·코드 작성은 Codex에 위임돼 있다. TDD의 Red → Green → 적절한 회귀·통합 검증 → 기록까지 진행한다.
6. 작업 단위를 마치면 완료 내용과 다음 목표·비용·트레이드오프를 다시 제시한다. `다음`·`continue`는 이 흐름을 이어가라는 뜻이며 미결 정책의 특정 선택에 대한 동의로 추정하지 않는다.

구체적인 사례는 충분히 자세히 설명한다. 먼저 정상 흐름을 설명하고, 주문·환불·결제·보유권의 식별자와 시각을 둔 뒤 무엇이 달라졌는지, 각 서비스 DB에 무엇이 있는지, 고객에게 어떤 일이 생기는지, 선택에 따라 어떻게 달라지는지 순서대로 설명한다. 존재하지 않는 환불에 완료 통지가 오는 상황을 평범한 메시지 지연처럼 갑자기 도입하지 않는다.

“환불 저장”은 접수·진행 상태의 저장일 수 있다. 접수 완료·보유권 정지 완료·PG 환불 완료·복구 요청·실제 복구 완료를 정확히 구분해서 말한다.

## 2. 먼저 읽을 근거

- [AGENTS.md](../AGENTS.md): 현재 완료 모듈, 경계·Java 작성·환경 기준.
- [설계 결정 29](decisions.md#full-refund-policy): 환불 정책과 선택 이유·대가.
- [환불 TDD와 검증 기록](testing.md#order-full-refund-tdd): 구현 단계, 실제 실패·통과, 아직 구현하지 않은 테스트 목록.
- [미결 질문 Q9](domain/open-questions.md#q9): 다음 정책·계약 결정. 정산은 Q12도 참고.

기존 문서의 단계별 “후속” 표기는 해당 기록 시점 기준이다. 가장 최근 기록과 현재 코드를 함께 읽는다. 제거된 구 환불 구현·이벤트는 새 계약이나 호환성 요구의 기준으로 사용하지 않는다.

## 3. 확정된 정책과 이유

| 쟁점 | 합의 내용과 이유 |
|---|---|
| 대상·소유권 | 주문 한 건의 전액 환불을 하나의 업무로 관리한다. 현재 주문당 상품 하나다. Order가 접수·자격·관리자 심사를 소유하고 Payment가 PG 실행을 맡는다. |
| 접수와 상태 | 접수 시 별도 Refund를 생성해 최초 접수·판정을 보존한다. Refund가 FSM을 가지며 Order의 원래 PAID·결제 사실을 덮어쓰지 않는다. 접수 기록은 환불 완료 기록이 아니다. |
| 자동 환불 | Payment 최초 결제 성공 확정 시각 `resultOccurredAt`부터 4시간 이내 접수는 조건 없이 자동 허용한다. 정확히 4시간인 접수도 포함한다. 초과하면 관리자 승인·반려가 필요하다. 4시간 자체는 별도 사업적 근거 없이 선택한 초기 학습용 정책값이다. |
| 처리 지연 | 최초 내구성 있는 접수의 시각·자격을 보존한다. 뒤늦은 처리·자동 재개에 현재 시각으로 자격을 재판정하지 않는다. 지연 책임을 고객에게 넘기지 않기 위함이다. 시간은 마이크로초 정밀도 `DATETIME(6)`를 사용하며 초 단위로 절삭하지 않는다. |
| 동시 접수 | 주문 행의 비관적 쓰기 잠금과 Refund 주문번호 DB 유일 제약을 사용한다. 경합이 높지 않을 것으로 예상해 단순한 순차 흐름을 선택했다. 외부 호출을 기다리며 잠금을 보유하지 않는다. |
| 지급 확인 | Order가 이미 가진 구매·환불 근거를 바탕으로 License 지급 확인을 기다린다. License가 지급과 `LicenseGranted` Outbox를 같은 트랜잭션으로 저장한다. License에 지급 전 정지 예약을 쌓는 방식은 선택하지 않았다. |
| 정지·PG 순서 | 자동 허용 또는 관리자 승인과 지급 확인을 모두 확보하면 정지 요청을 보낸다. 실제 License 정지 완료를 확인한 뒤 Payment 실행 요청을 만든다. PG 실행과 정지를 병행하지 않는다. 추가 접근을 최소화하려는 선택이다. |
| 심사 중 권한 | 4시간 초과로 심사 중이면 권한을 유지한다. 승인 뒤 정지 절차를 시작한다. |
| 응답·전달 | 접수·판정을 내구성 있게 저장한 뒤 응답하고, 후속 정지는 비동기로 반영한다. Outbox·Kafka를 사용하며 각 모듈의 업무 변경·Inbox·필요한 Outbox를 로컬 트랜잭션으로 묶는다. |
| 이벤트 검증 | 정지 요청에 환불·주문·구매자·상품·결제 연결값을 담고 License의 불변 구매 기록과 대조한다. 정지 요청·환불 실행 요청은 기존 Order 토픽과 orderNo 키를 사용한다. 같은 토픽·키만으로 전체 처리 순서가 보장되지는 않는다. |
| 정지된 상품 | 라이브러리 목록·상세에 남기고 ACTIVE/SUSPENDED를 표시한다. Download는 명시적 ACTIVE인 경우에만 새 URL을 발급한다. |
| 기존 파일·URL | 기존 presigned URL은 만료까지 사용할 수 있다. 다운로드된 파일의 회수·실행 시 권한 검사·기존 URL 즉시 무효화는 이번 환불 범위 밖이다. |
| PG 결과 미확정 | 서비스가 같은 실행 시도의 결과 조회·재개를 자동으로 맡는다. 타임아웃은 확정 실패가 아니다. 결과가 확인될 때까지 정지를 유지하며 시간 경과만으로 복구하지 않는다. 고객의 대기·운영 대응 비용은 남는다. |
| PG 확정 실패 | 보유권을 복구하고 실패 사유·복구 여부를 안내한다. 고객에게 돈을 돌려받거나 상품을 계속 보유하는 명확한 근거가 있어야 한다. 복구 요청 저장만으로 복구 완료라고 말하지 않는다. |
| 실행 이력 | 환불의 refundId와 각 실행의 attemptId를 구분하고 시도별 시작·결과·실패 사유를 보존한다(B안). 새 실행으로 이전 실패를 덮어쓰지 않는다. 미확정의 자동 재개는 같은 시도이며, 확정 실패 뒤 새로운 실행은 사용자의 명시적 재시도 방향이다. FAILED/미취소 조회와 실제 복구 완료 조건은 후속 B안으로 검증했다. 새 실행을 허용할 실패 종류와 추가 운영 확인 여부는 미결이다. |
| 근거 없는 정지 완료 | Order가 원래 환불·정지 요청을 확인할 수 없거나 연결값이 다르면 업무 진행을 거절·격리한다(A안). 외부 자료가 나중에 맞춰질 것으로 기대해 새 업무 대기를 만들지 않는다. DB 일시 장애는 재시도하며, DLT 발행 실패 시 원본 소비를 완료하지 않는다. 이를 환불 확정 실패나 복구 사유로 바꾸지 않는다. |
| 추적 | Tempo는 진단을 돕는다. 환불 상태·이력의 정본은 업무 DB다. 수집 장애·샘플링·보존 기간 때문에 trace가 없어도 미실행·실패라고 단정하지 않는다. 새 환불 흐름의 Tempo 전체 연결은 아직 검증 전이다. |

`attemptId`를 구분하기로 했다는 결정은 PG 멱등 키를 그대로 attemptId로 쓰기로 했다는 뜻이 아니다. 외부 중복 환불 방지는 별도 PG 계약이 필요하다.

2026-10-27 후속 논의에서 PG 키도 시도별로 구분하는 B안을 선택했다. 모의 PG의 `Idempotency-Key`는 attemptId로 매핑한다. 같은 시도의 응답 유실·자동 확인·재호출은 같은 키를 유지하고 최초 확정 성공·거절을 보존한다. 같은 키의 변경된 본문은 거절한다. 업무상 허용된 새 실행만 새 키를 쓰며, 키가 달라도 원결제의 전액 환불 성공은 한 번만 허용한다. PG 내부 구현 비용을 제외한 비교에서 기존 시도별 이력과 연결할 수 있다는 점을 Codex의 추천 근거로 설명한 뒤 사용자가 B안을 선택했다. PG 환불 구현·검증은 아직이며 상세 응답·실패 분류는 미결이다.

그 뒤 사용자는 가까운 시일 안에 모의 PG를 교체할 예정이며 모의 PG의 구현 비용을 최소화하고 싶다고 밝혔다. 실제 PG로 지금 교체하는 것을 추천하는지 물었고, 아직 전환 방식·대상 제공사를 선택하지 않았다. 앞서 제시한 '이미 환불된 결제에 다른 키로 요청한 경우의 PG 응답'도 미결이다. 모의 PG의 고유 계약을 더 설계하기 전에 실제 제공사 테스트 환경으로 전환을 시작할지·범위를 논의한다. 우리 시도 이력 보존과 외부 제공사의 멱등 응답 보장 기간은 구분해야 한다.

이어 사용자가 PG 테스트 환경 연결을 진행하겠다고 선택했다. 실제 제공사 테스트 연동을 먼저 진행하며 제공사 선택이 다음 쟁점이다. [PG 테스트 연동 계획](pg-test-integration-plan.md)은 현재 코드의 영향·첫 검증 범위 제안·남은 결정을 정리한 계획이며 구현·접속 완료 기록이 아니다.

이어 사용자가 기존 포트원 테스트 채널 사진을 제공했다. `tosspay_v2`는 토스페이 간편결제 채널이며 토스페이먼츠 카드 채널과 다르다. 준비된 포트원 V2 채널을 활용하는 방향으로 진행한다. 아직 실제 접속·결제·취소는 검증하지 않았다. 저장소 루트 `.env.portone-test`에 Store ID·채널 키·포트원 V2 API Secret의 빈 입력란을 만들었으며 Git에서 제외했다. 이 파일은 아직 앱이 자동으로 읽지 않는다. 화면의 PG API Key를 포트원 V2 Secret으로 사용하지 않고, 이미지 속 비밀값을 코드·문서에 복사하지 않는다.

이어 사용자가 직접 입력하기 어려워 Store ID·Secret을 전달했고 Codex가 Git 제외·600 권한인 `.env.portone-test`에 저장했다. 사용자에게 같은 값을 다시 요구하지 않는다. 2026-10-27 KST, 실제 포트원 `GET /payments`를 지정 Store ID·`isTest=true`·페이지 크기 1로 조회해 HTTP 200·정상 items/page·반환 0건을 확인했다. 결제·취소 생성은 0건이다. 비밀값·응답 원문 없이 요약만 `runs/portone-access-b797pwky/summary.json`에 보존했다. 채널 키 원문은 아직 없어서 사용자에게 요청했으며 사진에서 추정해 넣지 않았다. 이 결과는 API 인증 확인이며 채널 검증·결제창·승인·취소·업무 앱 연동 완료는 아니다.

그 뒤 사용자가 채널 키 원문을 전달해 로컬 설정을 완성했다. `tools/verification/portone-test-checkout.py`와 support 모듈·HTML·테스트를 추가했다. 독립 9,000원 TEST 거래의 결제창·서버 결과 대조·전액 취소 도구이며 업무 앱·주문·보유권·정산 연동은 아니다. 핵심 9건과 미확정 표시 누락의 Red → Green 뒤 최종 Python 20건·Python 컴파일·JavaScript 문법 검사를 통과했다. 원자료는 `runs/portone-checkout-tdd-_z78lxil/`이다.

최종 코드를 `python3 tools/verification/portone-test-checkout.py --run-dir runs/portone-checkout-live-20261027`로 기동했으며 기본 주소는 `http://127.0.0.1:18792`다. 자동 브라우저의 해당 Origin 접근 승인이 거절돼 화면·SDK의 실제 결제·취소는 검증 전이다. 다른 도구나 브라우저로 우회하지 않는다. 사용자가 직접 접속해 테스트 결제창을 확인하는 것이 다음 단계다. 같은 실행을 재개할 때 run-dir을 유지해 결제·취소 시도 키를 보존한다. 이 도구는 로컬 stdlib 서버만 사용하며 OCI DB·Kafka 검증을 했다고 주장하지 않는다.

이후 사용자가 curl·헤드리스 브라우저 확인을 명시적으로 요청했다. 요청에 따라 curl의 페이지/설정/서버 조회 200·토큰 없는 POST 403·변경 본문 400, 헤드리스 Chromium의 화면·SDK·버튼 상태·실제 토스페이 QR 인증 화면을 확인했다. 서버 재조회는 READY·9,000원·TEST·TOSSPAY_V2·취소액 0원이다. 사용자 휴대폰 인증·PAID·취소는 아직이며 업무 앱 반영도 아니다. 근거는 `runs/portone-http-uuvwsee1/`, `runs/portone-headless-iiY3Xt/`, `runs/portone-popup-wait-a1ntT5/`다. 로컬 서버가 종료돼 있어 재기동했으며 현재는 독립 프로세스의 PID·로그를 `runs/portone-checkout-live-20261027/server.pid`·`server.log`에 남겼다. 기동 상태를 먼저 확인하고 같은 run-dir을 재사용한다.

이후 사용자가 결제·취소까지 확인했다고 보고했고 Codex가 포트원 서버를 독립 재조회했다. TEST·TOSSPAY_V2·상점/채널 일치·KRW·총액/결제액/취소액 모두 9,000원·CANCELLED와 SUCCEEDED 취소 1건을 확인했다. 승인 2026-10-27 05:07:56 KST·취소 05:08:10 KST다. 검증 도구의 취소 재요청도 기존 결과·포트원 취소 ID·로컬 시도 키를 유지했다. 근거는 `runs/portone-checkout-live-20261027/provider-final-verification.json`이다. 독립 PG 테스트 연결의 첫 검증 단위는 완료했고 업무 앱의 결제·환불·보유권·정산 연동은 아직이다.

이후 사용자가 웹훅·주기 조회 병행의 B안부터 진행하기로 선택하고 별도 웹훅 시크릿을 전달했다. 시크릿은 `.env.portone-test`에 저장됐으니 다시 요구하지 않는다. Payment의 서명 검증·mapper·내구 접수·재조회 예약과 PORTONE_TEST PAID 조회 어댑터를 구현했다. 웹훅이 직접 결제를 확정하지 않고 기존 조회·결과/Outbox 경로를 사용한다. 모의 PG와 포트원의 대상을 분리하고 기본 모드는 변경하지 않았다. PAID 이외의 미결 결과는 확정 실패로 바꾸지 않는다.

TDD 원자료는 `runs/payment-portone-tdd-nuclayzz/`다. 로컬 단위 140·통합 소스 컴파일과 OCI 단위 140·통합 57(신규 9)건이 통과했다. 소스 946개를 대조했고 공유 컨테이너 18개·볼륨 55개와 실행 상태를 보존했다. 원격은 `/tmp/stove-portone-webhook-check-i9jZxBxW/work`, 수집 자료는 `runs/payment-portone-oci-e4wppb57/collected/results/`다. 실제 포트원 웹훅 전달·공개 주소·업무 SDK 시작·나머지 실패/환불/복구 정책까지 완료한 것은 아니다.

사용자는 기존 HTTPS 주소가 있다고 답한 뒤 Cloudflare CLI·OCI CLI로 확인해 달라고 요청했다. OCI 인증·ap-osaka-1 서버 1대·80/443 미수신·LB/NLB 없음까지 확인했고, `flarectl`은 설치됐으나 CF_API_TOKEN/CF_API_KEY가 없어 도메인을 조회하지 못했다. Cloudflare에 도메인이 없다고 단정하지 않는다. DNS·방화벽·공유 배포는 변경하지 않았다. 실제 웹훅 공개 주소는 Cloudflare 인증이나 기존 주소를 확인한 뒤 연결한다.

이어 사용자가 OCI 공개 IP를 쓰자고 제안하고 실행을 승인했다. `https://161.33.31.159/api/v1/payments/webhooks/portone`에 공개 신뢰 IP 인증서·자동 갱신·전용 nginx를 구성했다. 실제 포트원 TEST 결제창의 `Transaction.Ready`와 같은 웹훅의 재발송을 HTTP 200으로 수신하고 DB 접수 1개·최초 기록 보존을 확인했다. IP 인증서 갱신 dry-run과 systemd 서비스도 성공했다. 기존 방화벽의 80·443 허용을 재사용했고 공유 컨테이너 18개·볼륨 55개를 보존했다.

API·웹훅 비밀값은 계속 로컬에 있다. OCI nginx → OCI loopback 18883 → SSH 역방향 연결 → 로컬 Payment 18783 → 정방향 연결 → 전용 OCI MySQL 13383이다. 이 Mac의 Payment·SSH가 실행 중이어야 수신된다. 전용 DB의 합성 주문 픽스처로 검증했고 Kafka 리스너·Outbox 전송은 껐다. 새 실제 PG 거래는 READY이고 로컬 Payment는 PENDING이다. 실제 Order가 만든 결제와 SDK를 연결한 결제 성공 검증·실제 환불 실행을 완료한 것은 아니다. SDK noticeUrls를 썼으므로 콘솔 기본 URL을 다시 입력하도록 사용자에게 요구할 필요는 없다. 운영 설정·제약은 [edge 기록](../tools/verification/support/portone-edge/README.md), 원자료·로컬 재개 도구는 `runs/portone-public-https-cm9y22_z/`에 있다.

사용자가 다음 쟁점에서 결제창 정보 전용 API A안을 선택했다. `GET /api/v1/payments/{orderNo}/checkout`은 본인 MEMBER·PENDING·PORTONE_TEST를 확인하고 기존 paymentId·순결제액·상품명·주문 연결값과 공개 Store/채널/웹훅 URL을 SDK 형식으로 반환한다. 비밀값·새 결제 생성·PG 호출·상태 변경은 없다. 401/403/404/409·캐시 금지·반복 읽기·웹훅 뒤 PAID/재조회 거절을 검증했고 로컬 148건·OCI 단위 148/통합 60건이 통과했다. 현재 URI는 `PORTONE_WEBHOOK_URL`로 로컬 설정에 저장했으며 사용자에게 주소·키를 다시 요구하지 않는다. 단위 원자료는 `runs/payment-checkout-tdd-p2__qvdo/`, OCI 소스 959개·Red/Green·JUnit·보존 기록은 `runs/payment-checkout-oci-zoy5astj/collected/results/`다. 이번 OCI 기준 공유 컨테이너는 앞 단계의 edge·검증 DB를 포함한 20개이며 볼륨 55개·기존 상태를 보존했다.

로컬 검증 Payment도 새 API 빌드로 재개했으며 건강검사 UP·API 등록·미인증 401과 실제 포트원 웹훅 재발송 200·최초 접수 1건을 확인했다. 실행 중인 빌드 JAR를 덮어쓴 구 프로세스 종료 오류를 해결하기 위해 `resume-local.py`가 해시 이름의 `runtime/payment-<sha256>.jar` 복사본을 실행하도록 수정했다. 이후 빌드는 실행 파일을 덮어쓰지 않는다. 현재 실행 PID·로그·재개 도구는 계속 `runs/portone-public-https-cm9y22_z/`에 있고 비밀값은 로컬에만 있다.

그 뒤 사용자의 다음 지시로 실제 Auth 회원가입/PKCE → 계약 픽스처의 Catalog 공개 → 실제 Order HTTP → Outbox/Kafka → Payment 생성 → checkout API → SDK → 실제 준비 웹훅까지 연결했다. 후속 사용자 휴대폰 승인과 토스 테스트 페이지의 이동하기 처리 뒤 포트원·Payment·Order 모두 PAID·9,000 KRW로 확인했다. 최신 실행은 `runs/portone-order-live-j2flh033/`, OCI 경로는 `/tmp/stove-portone-order-check-kdxtkug4`다. 새 주문 `b3b36f62-625f-44d9-a444-23450b97e3c6`, 새 결제 `ce01f6e7-f3f5-4934-a169-a64e9983ab94`, 9,000 KRW를 유지한다. 이전 합성 Order 픽스처와 구별한다. QR은 이 디렉터리의 `tosspay-approval.png`이며 `browser-opened.json`·`provider-before-approval.json`에 SDK 정보 일치·TEST READY·주문 연결·웹훅 200 근거를 보존했다. 최종 provider-paid-verification.json·after-approval-db.tsv·confirmed-facts-after-replay.json에 상점/채널/주문/구매자/상품/금액과 요청·결과 ID 일치 근거가 있다. 실제 Paid 웹훅 재발송도 200이며 웹훅은 Ready/Paid 2개, OrderPlaced·OrderPaymentCompleted·OrderPaid 발행은 각각 SENT 1개를 유지했다. PAID 이후 checkout 409도 확인했다. PG 비용은 null이며 환불·License 지급·정산 연결은 후속이다.

현재는 새 OCI MySQL 13384·Kafka 19492와 로컬 Auth 18891·Catalog 18881·Order 18882·Payment 18783·검증 화면 18795를 사용한다. `resume-stack.py`가 앱·인프라 SSH·역방향 webhook SSH를 재개한다. 검증 UI는 `serve-checkout.py`, 헤드리스 결제창은 `open-checkout.cjs`이며 전용 계정 정보는 private.json(600)이다. 이전 `runs/portone-public-https-cm9y22_z/resume-local.py`는 현재 실행과 충돌하므로 동시에 실행하지 않는다. 이전 검증 DB·공유 배포·공개 인증서·갱신 타이머는 보존했다. 실제 PG 키는 여전히 루트 Git 제외 설정에만 있고 재입력하지 않는다.

이후 장기 미확정 환불 정책에서 사용자가 A안을 선택했다. 기존 시도별 멱등 키 B안은 유지한다. 최초 송신 준비를 DB에 커밋한 시각부터 3시간이 되면 자동 취소 명령을 막고 원래 PENDING·조회 입력을 보존한다. RecordRefundCancellationPreparation은 별도 짧은 트랜잭션과 attemptId 행 잠금으로 first_cancel_prepared_at을 한 번 저장하고 커밋 후 불변 권한을 반환한다. 외부 트랜잭션 안의 호출을 거절한다. auto_cancel_stopped_at은 최초 중단 관측을 보존한다. RefundCancellationPermission은 송신 직전 기한 재검사를 제공하며 같은 attemptId·원환불 입력을 유지한다. V16은 nullable DATETIME(6) 두 필드와 CHECK를 추가하고 기존 접수의 시각을 추정하지 않는다. PG HTTP 송신·조회 실행기·최종 결과 판정·운영 UI/알림은 아직 없고 시간 경과로 실패·보유권 복구·새 시도를 만들지 않는다.

이 A안 단위는 도메인 6·서비스 4건의 Red 이후 로컬 158건·bootJar·통합 컴파일을 통과했다. OCI full 실행 단위 158건, corrected 실행 통합 전체 65건이 통과했다. 최초 통합 2건 실패는 실제 DB가 아닌 테스트의 Repository 추상 실제 메서드 호출·Spring 예외 타입 예상 오류였고 운영 소스 없이 통합 픽스처 한 파일을 수정했다. corrected에 단위 태스크를 다시 실행한 것으로 기록하지 않는다. 소스 966개를 대조했고 공유 컨테이너 22개·볼륨 57개·기존 상태를 보존했다. 원자료는 runs/refund-window-tdd-u8jdb08z 및 runs/refund-window-oci-22cus_i6/collected/results, 원격 격리는 /tmp/stove-refund-window-check-hjlfQvVR/work이다. 다음은 실제 PG 취소 응답·조회에서 어떤 근거로 해당 시도의 성공을 확정할지 논의하며 미결 실패 분류·새 시도·복구 조건을 임의 구현하지 않는다.

실제 주문 검증 환경의 Payment도 검증된 해시 이름 runtime JAR로 교체했다. 건강검사 UP·V16 성공·기존 Order/Payment의 PAID 각 1건·refund_execution 0건을 확인했으며 PG 환불은 요청하지 않았다. 새 준비 서비스 활성화 근거는 `runs/refund-window-tdd-u8jdb08z/live-activation.json`과 `live-guard-runtime.json`이다.

2026-10-27 사용자는 조회로 해당 결제의 전액 취소가 확인되면 환불 업무를 완료하는 A안을 선택했다. 기존 작업은 01456a7로 커밋했다. 새 RefundPaymentLookup/PortOne 읽기 어댑터와 ReconcileRefundPayment가 원 PAID·PG 거래·상점·채널·TEST·주문·금액·성공 취소 합계를 검증하고 별도 확인 시각·성공 취소 근거·RefundPaymentConfirmed Outbox를 저장한다. Payment의 실행 PENDING은 귀속 불명을 유지하며 성공을 추정하지 않는다. Order의 전용 mapper/소비자와 ApplyRefundPaymentConfirmation은 현재 요청·실행·구매 사실을 대조하고 COMPLETED·최초 확인 지문·Inbox·실행 reconciliationRequired를 원자적으로 기록한다. 기존 PAID·보유권 정지는 보존한다. 자동 조회 스케줄/주기·PG 취소 송신·실패/복구·조사 UI·고객 환불 API는 후속이다. 최종 corrected 실행은 --rerun-tasks로 공통 Kafka 단위 187·Payment 단위 172·Order 단위 247·Payment 통합 69·Order 통합 75건을 모두 실패·오류·건너뜀 0으로 통과했다. 단위 합계 606·통합 합계 144건이다. 신규 통합은 Payment 4건과 Order 7건이며, 실제 DB의 근거/Outbox·완료/Inbox 롤백·동시 처리, Kafka 중복/offset·일시 실패 재시도·격리·DLT 발행 실패 시 미커밋을 검증한다. OCI 소스 986개를 대조했고 공유 컨테이너 22개·볼륨 57개·ID/시작/재시작/건강 상태를 보존했다. 추가·잔여 Testcontainers와 볼륨은 없다. 원자료 full·corrected 명령/소스/로그/JUnit·보존 기록은 runs/refund-confirmation-oci-5naahx04/collected/results, 원격 격리는 /tmp/stove-refund-confirmation-check-E5y3zflx/work이다. 현재 운영·테스트 소스는 corrected 해시와 같으며 검증 이후에는 문서만 갱신했다. 로컬 bootJar와 통합 소스 컴파일도 통과했다. [상세 계약](pg-test-integration-plan.md#2026-10-27-전액-취소-조회-확인-a안)을 따른다.

검증된 해시 이름 runtime JAR로 로컬 실제 주문 환경의 Order·Payment를 재개했다. 두 건강검사는 UP이고 Order V16·Payment V17 적용 로그를 확인했다. 반영 전후 본인 주문/결제 조회 응답 전체가 동일하며 기존 9,000원 결제는 PAID다. 실제 PG 취소 POST는 보내지 않았다. 근거는 runs/refund-confirmation-tdd-7pavkmo2/live-before-activation.json·live-activation.json·activation.log다.

2026-10-27 사용자는 자동화는 명확하고 간단한 경우에만 적용한다는 이유로 원결제 전액 잔액 조건 A안을 선택했다. 부분 취소의 잔액을 자동 보완하지 않는다. RequestRefundCancellation·RefundCancellationGateway/PortOne 송신 어댑터와 V18을 추가했다. 원액=amount=currentCancellableAmount를 고정하고 최초 JSON 본문·attemptId·준비 시각을 DB 커밋한 뒤 잠금 밖에서 송신한다. 부분 취소나 명시적 예상 잔액 오류는 cancellationBalanceChangedAt으로 보존해 이후 자동 POST를 막는다. 조회 전후의 3시간 기한 검사와 고정된 UTF-8 본문·따옴표로 감싼 멱등 키를 사용한다. 2xx만으로 시도 성공/실패를 만들지 않으며 별도 조회로 전액 취소가 확인되면 앞선 A안에 따라 고객 업무만 완료할 수 있다. 자동 스케줄·환불 API 연결과 실제 PG 송신 검증은 아직 없다. 단위 Red 15건·로컬 및 OCI Payment 단위 187건·통합 77건 통과 기록은 [최신 계약](pg-test-integration-plan.md#2026-10-27-원결제-전액-잔액-조건-a안)을 따른다. 원자료는 runs/refund-balance-tdd-t3jgf86j 및 runs/refund-balance-oci-gzscdzop, 원격은 /tmp/stove-refund-balance-check-YVJLAOCo/work이다.

최종 OCI full 실행에서 Payment 단위 187건·통합 77건이 실패·오류·건너뜀 0으로 통과했다. 신규 통합 8건은 실제 DB 커밋의 외부 관측·HTTP 본문/키·선행 부분 취소·조회/POST 사이 잔액 변화·처리 뒤 결과 미확정과 조회 복구·배포/재시작의 최초 본문 보존·3시간 기한·실제 flush 후 롤백·동시 처리/Outbox 1건을 검증했다. HTTP 공급자는 격리 계약 픽스처이며 실제 포트원의 경합 원자성이나 실제 자금 이동을 검증한 것으로 표시하지 않는다. 소스 996개를 SHA-256 대조했다. 공유 컨테이너 22개·볼륨 57개와 상태를 보존했고 추가·잔여 Testcontainers/볼륨이 없다. 원자료는 runs/refund-balance-oci-gzscdzop/collected/results/full 및 preservation.json이다. 로컬 단위·통합 컴파일·bootJar도 통과했다. 이번 실행은 Payment만 재검증했으며 이전 단계의 Order 247·공통 Kafka 187 단위와 Order 75 통합을 다시 실행한 것으로 합치지 않는다.

검증된 해시 이름 runtime JAR로 로컬 Payment만 재개해 건강검사 UP·V18 적용을 확인했다. Auth·Catalog·Order·SSH는 재시작하지 않았다. 반영 전후 본인 주문/결제 응답 전체가 동일하고 기존 9,000원 주문·결제는 PAID다. 실제 PG 취소 POST는 보내지 않았다. 근거는 runs/refund-balance-tdd-t3jgf86j/live-before-activation.json·live-activation.json·activation.log다.

2026-10-27 사용자는 검증된 취소 완료 응답으로 후속 조회를 기다리지 않고 시도 성공·환불 완료를 확정하는 A안을 선택했다. 정상 응답은 PG_RESPONSE 금융 완료 이벤트 한 건으로 양쪽 상태를 원자적으로 반영한다. 조회가 먼저 완료했다면 최초 금융 ID/시각/source는 유지하고 같은 취소 ID의 늦은 직접 응답을 별도 RefundExecutionSucceeded로 한 번 발행한다. Order는 늦은 성공과 원래 금융 이벤트가 역순으로 도착해도 최초 금융 결과와 시도 SUCCEEDED를 보존하고 조사 표시를 해제한다. 원 응답의 선택적 cancelledAt은 없으면 NULL이다. 미완성/불일치·REQUESTED/FAILED·통신 장애는 성공으로 만들지 않으며 확정 실패·복구 분류는 후속이다. 로컬 단위 640건·통합 컴파일·두 bootJar가 통과했고 OCI 단위 640·통합 160건이 통과했다. 원자료는 runs/refund-success-tdd-9nvmjlg2 및 runs/refund-success-oci-6tndqndq, 원격 격리는 /tmp/stove-refund-success-check-JeQiK1OD/work이다. [상세 계약](pg-test-integration-plan.md#2026-10-27-검증된-취소-완료-응답-a안)을 따른다.

OCI full 실행에서 공통 Kafka 단위 191·Payment 단위 195·Order 단위 254건(단위 합계 640), Payment 통합 81·Order 통합 79건(통합 합계 160)이 모두 실패·오류·건너뜀 0으로 통과했다. 신규 통합 8건은 성공 뒤 GET 장애와 무관한 확정, 실제 HTTP 응답을 지연한 조회 선행 경합, 성공/근거/Outbox 롤백과 동일 근거 재개, 동일 응답 동시 저장, Order 직접 성공/Inbox 롤백, 실제 Kafka의 정상 직접 완료 및 늦은 성공/금융 이벤트 양방향 순서·중복을 검증했다. 공급자는 HTTP 계약 픽스처이며 실제 PG 자금 이동 검증으로 확대하지 않는다. 소스 1005개를 SHA-256 대조했고 공유 컨테이너 22개·볼륨 57개의 ID/시작/재시작/건강 상태를 보존했다. 추가·잔여 Testcontainers/볼륨이 없다. 원자료 명령/소스/로그/JUnit·보존 기록은 runs/refund-success-oci-6tndqndq/collected/results/full 및 preservation.json이다. 검증 이후에는 문서만 갱신했으며 로컬 단위·통합 컴파일·두 bootJar도 통과했다.

검증된 해시 이름 runtime JAR로 로컬 Order·Payment를 재개해 건강검사 UP·Order V17/Payment V19 적용을 확인했다. Auth·Catalog·SSH는 보존했다. 반영 전후 본인 주문/결제 API 응답 전체가 동일하며 기존 9,000원 주문·결제는 PAID다. 실제 PG 취소 POST는 보내지 않았다. 근거는 runs/refund-success-tdd-9nvmjlg2/live-before-activation.json·live-activation.json·activation.log다.


2026-10-27 사용자는 REQUESTED 후 조회 간격 B안(최초 취소 준비 후 5·15·35초, 이후 1분)을 선택했다. DB에 최초 일정·단계·선점 임대·웹훅 깨우기를 저장하고 별도 스케줄러/최대 4개 조회 작업자로 GET만 재개한다. 기존 미확정 송신 직후 1회 GET은 유지한다. 재시작·긴 HTTP 후 밀린 조회는 건너뛰며, 3시간 POST 제한 이후에도 GET은 가능하다. 서명 검증된 취소 웹훅 첫 접수는 준비된 실행을 깨우고 같은 알림 재전달은 다시 깨우지 않는다. HTTP 중 깨우기 보존·새 임대를 늦은 이전 작업자가 덮어쓰지 못함·금융 완료 후 일정 해제를 검증했다. 자동 취소 POST 시작/재시도 간격과 확정 실패/복구는 여전히 미결이다.

최신 B안 검증은 로컬 Payment 단위 206·통합 컴파일·bootJar 및 OCI corrected Payment 단위 206·통합 93건(모두 실패/오류/건너뜀 0)이다. 신규 통합은 12건이다. 최초 full의 trace 테이블 정리 오류로 인한 실패 24건은 수정하고 --rerun-tasks 전체 재검증했으며 원자료를 함께 보존했다. 소스 1,014개를 대조하고 공유 컨테이너 22개·볼륨 57개와 상태를 보존했으며 잔여/추가 자원은 없다. 원자료는 runs/refund-lookup-tdd-1yp2c1o0 및 runs/refund-lookup-oci-lg92j8wa/collected/results/{full,corrected}, 원격은 /tmp/stove-refund-lookup-check-O4ndTMWD/work이다. Payment V20과 [최신 계약](pg-test-integration-plan.md#2026-10-27-requested-후-결과-조회-간격-b안)을 따른다. Order·공통 Kafka를 이번 단계에서 다시 검증한 것으로 합치지 않는다.



검증된 해시 이름 runtime JAR로 로컬 실제 주문 환경의 Payment만 재개하고 `PAYMENT_REFUND_LOOKUP_ENABLED=true`를 적용했다. Payment V20·건강검사 UP·서로 다른 결제/환불 스캔 스레드를 확인했다. Auth/Catalog/Order 및 SSH PID는 보존했다. 적용 전후 본인 주문/결제 API 응답 전체가 같고 기존 9,000원은 PAID다. 실제 PG 취소 POST는 보내지 않았다. 근거는 `runs/refund-lookup-tdd-1yp2c1o0/live-before-activation.json`·`live-activation.json`·`activation.log`다.


2026-10-27 사용자는 PG 실패 후 미취소 조회와 권한 복구 B안을 선택했다. REQUESTED/FAILED 정상 응답에서 우리 시도의 PG 취소 ID·원액·요청 시각·세금·사유를 보존한다. 추가 GET은 같은 취소 ID의 FAILED와 원결제 PAID/전액 미취소·진행 중/성공 취소 없음을 함께 확인한다. 이때만 Payment FAILED·실패/조회 근거·RefundExecutionFailed Outbox를 원자 저장한다. 다른 경로의 전액 취소가 먼저 확인되면 기존 금융 완료를 사용하고 복구하지 않는다. 조회 장애·부분/진행 취소·연결 근거 부재는 보류한다. 원 PAID와 최초 자격을 유지한다.

Order는 시도 FAILED·Inbox·LicenseRestorationRequested Outbox·PENDING_LICENSE_RESTORATION을 함께 저장한다. License는 현재 보유권/구매·실제 정지 요청/완료를 대조하고 ACTIVE·복구 이력·Inbox·LicenseRestored Outbox를 원자 저장한다. Order는 실제 완료의 현재 요청/시도/정지/구매를 검증한 뒤 환불 FAILED로 마친다. 요청만으로 완료하지 않는다. 옛 정지/복구 메시지가 다른 정지를 바꾸지 않으며 자동 새 시도는 없다. Payment V21·Order V18·License V8을 따른다.

최종 로컬 단위 공통 Kafka 203·Payment 213·Order 263·License 99건과 세 모듈 통합 컴파일/bootJar가 통과했다. OCI full의 전체 태스크는 모두 통과했고 후속 토픽 경계를 보강한 Order 단위 263/통합 83은 updated, 관리자 전액 취소 픽스처를 추가한 Payment 통합 전체 98은 payment-final에서 재검증했다. 나머지 최종 출처는 full의 공통 Kafka 203·Payment 단위 213·License 단위 99/통합 53이다. 모두 실패/오류/건너뜀 0이며 한 번의 전체 실행으로 합치지 않는다. 신규 통합은 14건이다. 소스 1,043개·공유 컨테이너 22개/볼륨 57개 보존·추가/잔여 자원 없음. 원자료는 runs/refund-restoration-tdd-ur27gau4 및 runs/refund-restoration-oci-fg84qzci/collected/results/{full,updated,payment-final}, 원격은 /tmp/stove-refund-restoration-check-EpKxdIk5/work다. [최신 B안](pg-test-integration-plan.md#2026-10-27-실패-후-미취소-조회와-권한-복구-b안)을 따른다. 실제 PG 환불이나 전체 실거래 환불 E2E의 완료 기록이 아니다.



검증된 해시 이름 runtime JAR로 실제 주문 검증 환경의 Order·Payment만 재개했다. 건강검사는 UP이고 Order V18·Payment V21 적용을 확인했다. Auth·Catalog·SSH PID와 읽기 전용 환불 조회 활성화를 보존했다. 적용 전후 본인 주문/결제 API 응답 전체가 동일하고 기존 9,000원은 PAID다. 실제 PG 취소 POST는 보내지 않았다. 이 환경에서 License 서비스를 새로 실행한 것은 아니며 License 복구는 OCI 격리 통합으로 검증했다. 근거는 `runs/refund-restoration-tdd-ur27gau4/live-before-activation.json`·`live-activation.json`·`activation.log`다.

## 4. 구현 완료 지점

현재 흐름은 다음과 같다. 전액 잔액 조건의 PG 취소 송신 서비스와 전액 취소 조회→Payment 근거/Outbox→Order 완료 경로를 구현했다. 결과 GET 스케줄과 실패/권한 복구 B안은 연결했다. 자동 취소 POST/API 연결·실제 PG 송신 검증은 후속이다.

1. Order `RequestRefund`가 PAID 주문의 본인 여부·결제 근거·기한을 검증하고 최초 Refund를 저장한다. 지급 미확인이면 `PENDING_LICENSE_GRANT`, 지급 확인이 있으면 정지 요청과 `PENDING_LICENSE_SUSPENSION`, 기한 초과면 `PENDING_REVIEW`다.
2. License `GrantLicense`가 최초 지급에 `LicenseGranted` Outbox를 원자적으로 저장한다. Order `ApplyLicenseGrant`가 지급 사실을 저장하고 허용된 환불의 대기를 재개한다. 환불보다 먼저 도착한 지급 확인도 저장한다.
3. License `ApplyLicenseSuspension`이 로컬 구매 근거를 검증하고 보유권 정지·정지 이력·Inbox·`LicenseSuspended` Outbox를 함께 저장한다. 지급이 없으면 완료를 만들지 않는다. 기존 `OrderPaid` 재전달로 정지를 해제하지 않는다.
4. Download `LicenseHttpClient`는 ACTIVE만 허용한다. SUSPENDED는 403, 누락·알 수 없는 상태는 503이며 Catalog/S3로 진행하지 않는다.
5. Order `ApplyRefundSuspension`이 원래 주문·환불·지급·실제 정지 요청 ID와 완료를 대조한다. 첫 `RefundAttempt`·Inbox·`RefundExecutionRequested` Outbox와 `PENDING_PAYMENT_REFUND` 전이를 함께 저장한다. 같은 완료가 재전달돼도 새 실행을 만들지 않는다. 전용 소비자의 근거 불일치만 즉시 DLT로 보내며 다른 소비자 정책은 유지한다.
6. Payment `ReceiveRefundExecution`이 로컬 PAID 결제의 주문·paymentId·구매자·실제 청구액·통화를 검증한다. refund_execution·Inbox·별도 시도별 추적 부모를 함께 저장한다. PG는 호출하지 않으며 최초 결제·PG 비용·구매 추적 부모도 바꾸지 않는다. 같은 요청의 재전달은 최초 값을 유지하고, 미확정 실행 중 새 시도·변경된 본문·식별자 재사용을 거절한다. 접수 직후 상태는 PENDING이며, 검증된 직접 성공 응답에서 SUCCEEDED를 기록하고 최신 B안의 실패/미취소 조회가 함께 입증되면 FAILED를 기록한다.

7. Payment의 전액 잔액 조건 송신·직접 성공 저장·조회 확인과 Order의 금융 완료/시도 성공 수신은 최신 A안 기록을 따른다. 정상 직접 응답은 후속 GET 없이 완료하며, 조회 선행이면 최초 금융 이벤트를 보존하고 늦은 시도 성공만 보완한다. 결과 GET 스케줄은 후속 B안으로 연결했다. FAILED/미취소 확인과 실제 권한 복구는 최신 B안으로 연결했다. 자동 취소 POST/API·실제 PG 송신·HTTP 오류 세부 분류는 후속이다.

조회 확인 서비스가 반환한 전액 취소 근거는 Payment Outbox와 Order 전용 소비자를 통해 환불 완료로 연결된다. 이때 실행 PENDING·결과 미확정은 유지하고 조사 표시를 남긴다. 취소 송신 서비스·검증된 성공 응답 귀속·결과 GET 스케줄과 FAILED/미취소 확인·보유권 복구를 구현했다. 자동 취소 POST/API 연결은 후속이다. 환불 접수·조회 HTTP API와 관리자 승인·반려 유스케이스도 아직 구현하지 않았다. RequestRefund 서비스의 완료를 API 제공 완료로 표현하지 않는다.

읽기 시작할 코드:

- [Order 환불 접수](../apps/order/src/main/java/com/stove/order/core/service/RequestRefund.java), [지급 확인](../apps/order/src/main/java/com/stove/order/core/service/ApplyLicenseGrant.java), [정지 완료 반영](../apps/order/src/main/java/com/stove/order/core/service/ApplyRefundSuspension.java).
- [Refund](../apps/order/src/main/java/com/stove/order/core/domain/Refund.java), [RefundAttempt](../apps/order/src/main/java/com/stove/order/core/domain/RefundAttempt.java).
- [License 정지](../apps/license/src/main/java/com/stove/license/core/service/ApplyLicenseSuspension.java), [Download의 권한 확인](../apps/download/src/main/java/com/stove/download/adapter/LicenseHttpClient.java).
- [Payment 접수](../apps/payment/src/main/java/com/stove/payment/core/service/ReceiveRefundExecution.java), [실행 모델](../apps/payment/src/main/java/com/stove/payment/core/domain/RefundExecution.java), [실행 요청 계약](../common/kafka/src/main/java/com/stove/common/kafka/contract/payload/RefundExecutionRequestedEvent.java).
- [Payment 신규 통합 테스트](../apps/payment/src/integrationTest/java/com/stove/payment/RefundReceptionIntegrationTest.java).

마이그레이션은 Order V12~V15, License V6~V7, Payment V14를 확인한다. License V6의 구 Outbox 보존·DEAD 전환과 Order V15의 기존 정지 요청 상관키 복원은 **기존 구매의 LicenseGranted 누락 복구가 아니다**. 기존 구매의 환불을 지원에서 제외하기로 합의한 적도 없다.

## 5. 커밋과 검증 근거

초기 인계 작성 전 요청에 따라 다음 5개 작업 단위로 커밋했다. 이는 초기 인계 시점의 기록이며 이후 누적 커밋은 아래에 구분한다.

| 커밋 | 작업 단위 |
|---|---|
| `fc39eab` | 보유권·환불 실행 공통 이벤트 계약 |
| `be43a09` | Order 환불 접수·지급 대기·정지 확인·실행 이력·요청 |
| `af3ce8f` | License 정지·완료 발행과 Download 신규 URL 차단 |
| `c57f76e` | Payment 환불 실행 요청 검증·멱등·내구 접수 |
| `ff9a6d9` | 설계 결정·TDD·OCI 검증 기록 |

2026-10-27 누적 구현 커밋: `01456a7`의 포트원 결제창/3시간 제한 이후 사용자가 선택한 전액 취소 조회 확인·전액 잔액 조건·직접 성공 응답·조회 주기 B안·실패/권한 복구 B안을 다음 단위로 커밋했다. 운영·테스트 소스는 최신 OCI 검증 해시와 같으며 기록 변경은 문서뿐이다. 비밀값과 runs의 실행 자료는 커밋에서 제외했다.

| 커밋 | 작업 단위 |
|---|---|
| `00f24ee` | 환불 결과·권한 복구 공통 이벤트 계약 |
| `b31c436` | Payment 취소 송신·결과 확인·내구 조회·실패 근거 |
| `838d5f8` | License 실제 권한 복구·상관키·멱등/롤백 |
| `279b895` | Order 환불 완료·실패/복구 요청·실제 복구 완료 반영 |

이 표와 단계별 결정·실행별 검증·인계 갱신은 별도 문서 커밋에 포함한다. 커밋만을 위해 동일 소스의 테스트를 다시 실행한 것으로 표시하지 않는다. 다음은 복구가 끝난 실패 건의 명시적 새 실행 계약이며 아직 선택·구현하지 않았다.

검증 수치는 단계별 기록이다. 모든 모듈을 최종 소스에서 한 번에 재실행한 것으로 합치지 않는다.

| 단계 | 검증과 원자료 |
|---|---|
| License·Download | License 단위 95/통합 48, Download 단위 99/통합 10 통과. `runs/license-suspend-oci-6onh6n_u/`에 원자료 보존. 기존 URL 만료 전 접근과 실제 MinIO 경계도 검증했다. |
| Order 정지 완료 | 단위 241, 통합은 전체 실행에서 통과한 64건 + 최종 Kafka 선별 재실행 4건. 단일 전체 실행 68건의 일괄 성공이라고 표시하지 않는다. 운영 코드는 같고 테스트 픽스처를 수정했다. `runs/refund-completion-oci-8wcbu9hl/`에 초기 실패·수정·선별 XML 출처를 보존했다. |
| 최신 Payment 접수 | 로컬 단위 123 및 통합 소스 컴파일. OCI Payment 단위 123/통합 48(신규 14) 통과. 공통 Kafka 183은 변경 없는 입력의 Gradle 캐시 결과다. 실패·오류·건너뜀 0. `runs/payment-refund-reception-tdd-fbrwbk1q/`, `runs/payment-refund-reception-oci-prqa5s5l/`에 원자료 보존. |

최신 OCI 작업 디렉터리는 `/tmp/stove-payment-refund-reception-check-ke_8kzmp/work`다. 920개 소스를 SHA-256으로 대조했고 `validation-summary.json`에 결과를 남겼다. 실행 명령:

```sh
./gradlew :common:kafka:test :apps:payment:test :apps:payment:integrationTest --continue --console=plain --no-daemon --max-workers=2
```

Payment 신규 통합은 실제 MySQL의 원자성·롤백·재처리·동시 중복·유일 제약·연결값 검증, 격리된 모의 PG 중단 중 접수, 실제 Kafka 중복의 offset 커밋·첫 추적 부모 보존·잘못된 금액 DLT를 검증했다. 실제 PG 환불 HTTP·Order 결과 반영·License 복구를 연결한 E2E는 아니다.

OCI 검증 전후 공유 컨테이너 18개와 볼륨 55개를 보존했고 잔여 Testcontainers도 없었다. 공유 배포를 재시작하지 않았다. 원자료는 `.gitignore` 대상 `runs/`와 원격 격리 디렉터리에 있으며 커밋에는 코드·관련 문서만 포함했다.

## 6. 다음 세션의 첫 목표와 남은 결정

원래 첫 목표는 **모의 PG 환불 승인·조회·중복 방지 계약을 정하는 것**이었다. 후속으로 시도별 키·실제 제공사 테스트 전환을 선택했고, 독립 9,000원 결제·전액 취소를 확인했다. 사용자가 B안(웹훅·주기 조회 병행)을 선택해 첫 Payment 기반을 구현·OCI 검증했고, OCI 공개 IP HTTPS 경로에서 실제 준비 웹훅·재발송 수신까지 확인했다. 이후 전용 결제창 정보 API A안도 구현·OCI 검증했다. 해당 연결은 사용자 휴대폰 승인과 테스트 페이지 결과 반환 뒤 실제 포트원·Payment·Order PAID 및 Outbox/중복 대조까지 완료했다. 같은 9,000원 PAID 주문·결제를 후속 환불 검증 근거로 보존한다. 후속으로 3시간 경과 자동 재전송 중단 A안의 DB 준비·권한 경계를 구현·검증했다. 후속으로 조회 확인 A안을 선택했다. 이후 원액=취소액=예상 잔액의 A안을 선택해 송신 서비스를 추가했다. 이후 검증된 취소 완료 응답 A안으로 직접 성공 귀속·고객 완료·늦은 응답 보완을 구현했다. 이후 REQUESTED의 결과 GET 간격 B안도 구현·검증했다. 이후 FAILED와 미취소 조회를 함께 검증해 실제 보유권 복구로 연결하는 B안도 구현·검증했다. 다음은 복구 뒤 명시적 새 실행의 계약·자동 취소 POST 시작/재시도 주기·나머지 HTTP 오류 분류를 한 쟁점씩 논의한다. 실제 9,000원 주문의 License 지급·정지·환불 결과·복구·정산을 연결한 E2E는 후속이다. 초기 합성 주문 READY·별도 독립 결제/취소 기록과 구별해, 최신 실제 Order에서 발생한 TEST 결제 성공도 검증했다. 주문 접수 직후 아직 Payment가 없을 때의 대기 안내·실제 PG 실패 분류가 필요하면 별도 쟁점으로 다룬다. 나머지 HTTP 오류 분류·새 실행·모순 결과의 운영 처리는 후속이며 임의로 확정하지 않는다. Cloudflare 인증·같은 비밀값·이미 합의한 주소를 다시 요구하지 않는다.

첫 사례는 이렇게 구체화할 수 있다. 주문 O1은 9,000원을 결제했고 환불 R1·시도 A1의 권한 정지와 Payment 접수까지 끝났다. Payment가 PG에 환불을 요청했는데 PG는 돈을 돌려준 후 응답이 유실됐다. 우리 DB에는 아직 PENDING만 있다. 이때 같은 요청의 재호출·조회가 중복 환불 없이 기존 결과로 연결돼야 한다. 이 사례의 계약을 먼저 정하고, 확정 거절 후 고객이 다시 요청하는 다른 사례와 구분한다.

미결 사항:

- 조회의 전액 취소 확인 및 원결제 전액 잔액 조건 A안은 선택·구현했다. POST의 기본 필드·본문/키 보존과 검증된 SUCCEEDED 응답의 시도 성공 귀속은 구현했다. REQUESTED/FAILED 연결·미취소 조회·실제 복구 B안은 구현했고 나머지 HTTP 오류 분류는 미결이다. attemptId → PG 키의 동일 매핑은 유지하며, 이미 취소된 결제는 조회로 검증한 고객 업무 완료와 실행 결과 불명을 구분한다.
- 결과 미확정·일시 장애·확정 실패의 분류, 자동 취소 POST 시작/재시도 간격, 장기 대기 고객 안내·운영 개입 기준. 결과 GET 간격은 B안으로 결정·검증했다.
- 어떤 확정 실패에 고객의 새 실행이 유효한지, 이전 시도의 지연 결과와 현재 실행 대조, 실제 보유권 복구 완료 후 재정지·실행 허용 조건.
- 검증된 직접 성공·FAILED/미취소 조회와 보유권 복구 요청/완료 계약·원자적 반영은 구현했다. 나머지 오류·모순 결과의 운영 처리는 후속이다.
- 관리자 승인·반려 경합, 판정 변경·반려 후 재신청, 환불 접수·조회·관리자 HTTP 계약과 권한.
- 기존 지급 건의 확인 누락 보완·재처리, Outbox 재시도와 소비 순서·재시작 후 재개, 새 흐름의 Tempo 검증.
- 접수 시각이 결제 확정 시각보다 앞서는 비정상 시간 관계, 기한 정책 변경의 과거 적용.
- 환불 후 재구매·이전 주문 지연 이벤트로부터 새 보유권 보호, PG 비용 반환·판매자 몫·정산·마감 이후 조정.

## 7. 실행·구현 시 유지할 기준

- 사용자는 `ssh sng`를 통한 OCI 접근·검증을 명시적으로 허용했고 이후 같은 기능의 검증도 이어서 지시했다. 로컬 OrbStack이 불가했던 상황에서 격리 OCI Testcontainers를 사용했다. 편집·빠른 테스트·컴파일은 로컬에서 하고 인프라가 필요한 검증은 이 승인 범위에서 이어간다.
- OCI는 매번 별도 `/tmp/stove-...-check-<random>/work`를 사용한다. 공유 Compose·컨테이너·볼륨을 내리거나 전체 prune하지 않는다. 실행별 소스 해시·명령·로그·JUnit과 공유 자원 전후 상태를 남긴다. 기존 `runs/*-oci-*/verify.py`, `preserve.py`를 참고하되 현재 범위에 맞게 읽고 사용한다.
- 외부 계약은 호출 모듈 `core.port`, 구현은 `adapter`, 설정 바인딩·객체 조립은 `config`다. 외부 이벤트는 `api.listener`의 mapper가 검증해 유효한 내부 입력으로 변환한다. 서비스는 잠금·Inbox·업무 변경·Outbox 트랜잭션을 조율한다.
- Java는 명시적 타입·명시적 import를 사용하고 `var`·와일드카드·충돌 없는 FQN을 쓰지 않는다. 기존 아키텍처 규칙의 서비스 동사를 따른다(`ApplyLicenseSuspension`, `ApplyRefundSuspension`).
- Red는 동작 미구현 때문에 실패하는지 확인한다. 순수 단위 성공으로 실제 DB 원자성·외부 전달을 증명하지 않는다. 단계별 검증 결과와 전체 기능 완료를 구분한다.
- 사용자 요청이나 적용되는 저장소 지시 없이 하위 에이전트를 생성하지 않는다. 환불 전체가 완료됐다고 기록하거나 완료 모듈 목록을 이번 부분 구현만으로 갱신하지 않는다.

## 새 세션에 붙여 넣을 시작 요청

```text
/Users/jade/Projects/esd-platform에서 전액 환불 설계·구현을 이어가자.
AGENTS.md와 docs/refund-session-handoff.md를 먼저 읽고 최신 코드·git 상태를 대조해줘.
현재는 Payment 접수·3시간 POST 제한·전액 잔액 조건·직접 성공/조회 확인·결과 GET 주기와 FAILED/미취소 확인 후 실제 권한 복구 B안까지 구현·검증했어. 최신 검증 기록과 미결 범위를 먼저 대조해줘.
다음은 복구 뒤 명시적 새 실행의 계약과 자동 취소 POST 시작/재시도 주기·나머지 HTTP 오류 분류를 한 쟁점씩 논의하자.
각 쟁점의 배경·구체적인 사례·대안·트레이드오프와 네 의견을 설명한 뒤 내가 선택하고 이유를 말할 시간을 줘.
내 선택의 반례와 놓친 비용을 검토하고, 합의한 뒤에는 테스트·코드 작성과 검증을 맡아줘.
TDD로 진행하고, 인프라 검증은 승인한 ssh sng OCI의 격리 환경을 사용하며 공유 자료를 보존해줘.
```
