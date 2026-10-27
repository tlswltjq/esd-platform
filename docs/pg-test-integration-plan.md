# PG 테스트 환경 연동 계획

2026-10-27. 사용자가 실제 제공사 테스트 연동을 먼저 진행하겠다고 선택한 뒤 기존 포트원 테스트 채널 사진을 제공했다. 포트원 V2·토스페이(`tosspay_v2`) TEST 채널의 독립 9,000원 결제·전액 취소를 검증했다. 단계별 근거는 아래에 기록한다. 업무 앱의 주문·보유권·정산 연동 완료는 아니다.

## 목적과 확정한 기준

- 모의 PG는 가까운 시일 안에 교체할 예정이므로 추가 구현 비용을 최소화한다.
- 모의 PG의 환불 응답·실패 계약을 더 설계하기보다 선택한 실제 제공사의 계약을 기준으로 연동한다.
- 기존 환불 책임·권한 정지 순서·시도별 이력과 B안은 유지한다. 시도별 외부 키의 표현·보장 기간은 제공사 계약에 맞춘다.
- 외부 호출은 호출 모듈의 `core.port`, HTTP 구현은 `adapter`, 환경 바인딩·조립은 `config`에 둔다.
- 편집·빠른 테스트는 로컬에서 수행한다. 인프라 검증은 승인된 `ssh sng` OCI의 실행별 격리 환경을 사용하고 공유 자원을 보존한다.

## 현재 코드에서 확인한 영향

| 현재 경계 | 실제 인증 결제 연동 시 검토할 내용 |
|---|---|
| `ReceiveOrderPayment`는 주문 이벤트로 결제를 내구 접수한다 | 주문·구매자·금액 확정과 중복 접수 검증을 유지한다 |
| `Payment.receive`는 즉시 처리 가능한 PENDING을 만든다 | 구매자 인증 전 대기와 PG 승인 결과 미확정을 구분할 방법이 필요하다 |
| `ResolvePayment`는 PENDING을 찾아 `PgGateway.charge`를 호출한다 | 인증 전 승인 호출 방지, 인증 결과의 내구 접수와 결과 조회·재개 흐름이 필요하다 |
| `PgChargeRequest`는 paymentId·orderNo·amount·currency만 가진다 | 제공사가 요구하는 인증·결제 식별자와 기존 주문 연결을 검토한다 |
| `PaymentController`는 본인 목록·상세 GET만 제공한다 | 결제 시작·인증 후 결과 처리에 필요한 최소 API를 논의한다 |
| 저장소에 별도 프런트엔드나 결제 화면이 없다 | 테스트 결제용 최소 화면·완료 경로를 준비한다 |
| Settlement는 모의 PG 거래·비용·명세를 직접 조회한다 | 새 제공사의 비용·정산 지원을 별도로 확인한다. 모의 비용이나 명세를 실제 제공사의 근거로 사용하지 않는다 |

## 제안하는 첫 검증 단위

처음에는 카드 결제 한 종류를 제안했으나, 사용자가 준비한 채널은 토스페이 간편결제다. 이 채널을 활용해 테스트 결제 1건의 생성 → 서버 조회·근거 검증 → 전액 취소 → 취소 결과 조회를 연결하는 방향으로 준비한다. 토스페이먼츠 카드 채널과 구분하며 브라우저 SDK의 결제수단은 `EASY_PAY`다. 같은 제공사에서 생성한 테스트 결제를 사용하며 기존 모의 PG 거래를 가져가 취소할 수 있다고 가정하지 않는다.

이후 검증된 외부 계약을 기존 Order·Payment·License·Download 환불 흐름에 연결한다. 각 단계에서 실제로 검증한 범위만 기록한다. 테스트 결제 API의 성공과 전체 환불 기능 완료를 구분한다.

## 업무 연동의 다음 결정

1. 사용자가 준비한 포트원 V2·토스페이 테스트 채널을 활용한다. 사진은 설정이 준비됐다는 근거이며 실제 API 접근·승인 성공의 근거는 아니다.
2. 독립 결제·취소 확인 뒤 사용자는 웹훅·주기 조회 병행의 B안부터 진행하기로 선택했다. 서명 검증·내구 접수·조회 재개·PAID 승인 반영의 첫 기반을 구현·검증했으며, 실제 주문의 SDK 결제 시작·공개 수신 주소 연결과 나머지 PG 상태 정책은 후속이다.
3. 계정·테스트 설정·콜백/웹훅 도달 경로를 확인한다. 비밀값은 서버 설정으로 주입하고 저장소·로그·대화에 노출하지 않는다.
4. 합의한 동작부터 TDD Red → Green → 회귀·격리 OCI 검증을 수행한다. 미결 정책을 테스트 기대값으로 먼저 고정하지 않는다.

## 로컬 연동 정보 준비

저장소 루트의 `.env.portone-test`에 아래 세 값을 로컬에서 입력한다. 재생성용 빈 예제는 `tools/verification/portone-test.env.example`이다. `.env.portone-test`는 Git에서 제외하고 파일 권한은 600으로 둔다. 업무 앱이 자동으로 읽는 설정은 아니며 아래 독립 검증 도구가 읽는다. 비밀값을 사진에서 옮기거나 문서에 기록하지 않았다.

| 변수 | 포트원 콘솔에서 확인할 값 |
|---|---|
| `PORTONE_STORE_ID` | 연동 관리의 상점 ID (`store-...`) |
| `PORTONE_CHANNEL_KEY` | 준비한 테스트 채널의 채널 키 (`channel-key-...`) |
| `PORTONE_API_SECRET` | 식별코드·API Keys의 V2 API에서 발급한 Secret |

채널 수정 화면의 PG API Key는 포트원 서버 API 인증용 Secret과 다르다. 사진으로 읽은 채널 키를 추정해 넣지 않고 콘솔에서 복사한 원문을 사용한다. 실제 키 형식이나 접두사만으로 테스트 거래라고 판단하지 않으며, 사용할 채널이 테스트 채널인지 함께 확인한다.

## 2026-10-27 읽기 전용 API 인증 확인

사용자가 로컬 파일에 직접 입력하기 어려워 전달한 Store ID·API Secret을 Codex가 `.env.portone-test`에 저장했다. 값은 문서·로그에 재기록하지 않았으며 파일은 Git에서 제외하고 600 권한을 확인했다. 이후 세션에서도 저장된 설정을 먼저 확인하고 사용자에게 같은 값을 다시 요구하지 않는다.

공식 OpenAPI의 `GetPaymentsBody`·`PaymentFilterInput`을 확인하고 `GET /payments`에 지정한 Store ID·`isTest=true`·페이지 0·크기 1을 사용했다. 실제 포트원 서버에서 HTTP 200과 정상 items/page 응답을 받았으며 반환된 테스트 결제는 0건이었다. 이는 API 인증과 해당 필터 조회가 성공했다는 근거다. 채널의 실제 유효성·테스트 결제 승인·환불·웹훅·업무 DB 반영을 검증한 것은 아니다.

읽기 요청만 수행했으며 결제·취소 생성은 0건이다. 응답 원문·Authorization·비밀값을 로그로 남기지 않고 요약만 `runs/portone-access-b797pwky/summary.json`에 보존했다. 소스 변경사항에 Secret이 포함되지 않았음도 확인했다. 다음 결제창 연결에 필요한 채널 키 원문은 사용자에게 요청했으며 사진에서 추정해 넣지 않았다.

## 2026-10-27 독립 테스트 결제 검증 도구

이후 사용자가 채널 키 원문을 전달했고 로컬 설정의 세 값을 모두 준비했다. 포트원 브라우저 SDK로 테스트 결제창을 열고 서버 API로 결과를 대조하는 독립 도구를 추가했다. 업무 앱·DB·Kafka·보유권·정산은 변경하지 않는다. 도구의 테스트와 실제 제공사 승인·취소 검증은 구분한다.

```sh
python3 tools/verification/portone-test-checkout.py
python3 -m unittest discover -s tools/verification -p test_portone_checkout.py -v
```

기본 주소는 `http://127.0.0.1:18792`이며 loopback에만 바인딩한다. 브라우저에는 Store ID·채널 키·서버에서 고정한 검증 결제 번호·9,000원·EASY_PAY만 전달하며 API Secret은 서버에 둔다. 같은 Origin·Host·실행별 토큰을 확인하고 다른 결제 번호나 금액을 요청 본문으로 지정할 수 없게 했다.

결과는 포트원 서버에서 검증 결제 번호·상점·V2·TEST 채널·채널 키·TOSSPAY_V2·KRW·금액을 대조한다. 이 도구의 좁은 검증 범위에서는 할인·부분 취소를 지원하지 않는다. 전액 취소는 검증된 PAID·9,000원·미취소 거래만 허용하며 취소 가능 잔액도 명시한다. 요청 전 취소 시도 키를 로컬 상태 파일에 원자적으로 저장하고 재시작·응답 유실 뒤에도 동일 키를 따옴표로 감싸 전송한다. POST 성공만으로 취소 완료를 표시하지 않고 다시 조회해 CANCELLED·총 취소액·SUCCEEDED 내역을 확인한다. 미확정 요청은 미확정으로 안내한다.

실행별 `runs/portone-checkout-*/state.json`을 보존한다. `--run-dir`로 같은 실행을 재개할 수 있으며 결제 번호와 취소 시도 키를 유지한다. 3시간 멱등 보장 기간 이후 취소 완료가 확인되지 않으면 이 검증 도구는 재전송·새 키 생성을 중지한다. 이는 검증 도구의 제한이며 Payment의 장기 미확정 운영 정책이나 새 업무 시도의 허용을 결정한 것은 아니다.

첫 핵심 9개 테스트는 미구현 Red → Green을 확인했다. 추가로 취소 접수만 되고 최종 확인되지 않은 결과의 표시 누락을 Red → Green으로 수정했다. 최종 Python 테스트 20개, Python 컴파일과 JavaScript 문법 검사를 통과했다. HTTP 요청 파서 검증은 메모리 스트림과 계약 픽스처로 수행했으며 네트워크·브라우저·DB를 사용하지 않는다. 원자료는 `runs/portone-checkout-tdd-_z78lxil/`이다. OCI 인프라 검증을 수행한 것으로 확대하지 않는다.

최종 코드를 `runs/portone-checkout-live-20261027` 실행 상태로 기동했다. 자동 브라우저의 localhost 접근은 Origin 승인 단계에서 거절돼 화면·SDK 결제창의 실제 동작은 검증하지 못했다. 다른 도구·브라우저로 우회하지 않았으며 사용자 직접 화면 확인을 기다린다. 실제 PG 결제·취소 생성이나 업무 앱 연동 완료를 주장하지 않는다.

## 2026-10-27 사용자 요청에 따른 curl·헤드리스 검증

이후 사용자가 curl이나 헤드리스 브라우저로 확인할 수 있는지 명시적으로 요청했다. 이 요청에 따라 읽기 전용 curl 검사와 별도의 헤드리스 Chromium 검증을 진행했다. 앞선 자동 브라우저 접근 거절 기록과 구분한다.

첫 curl 검사에서는 이전 로컬 서버가 종료되어 HTTP 000이었으므로 같은 run-dir로 재기동했다. 이후 페이지·공개 설정·인증된 서버 결과 조회는 HTTP 200, 실행 토큰 없는 POST는 403, 다른 결제 번호를 지정한 본문은 400을 확인했다. 원자료는 `runs/portone-http-uuvwsee1/summary.json`이다.

번들 Playwright와 설치된 Chromium headless shell을 사용해 화면 렌더링·SDK 로드·버튼 상태를 확인했다. SDK와 결제/조회 버튼은 정상이고 승인 전 취소 버튼은 비활성화됐으며 페이지 JavaScript 오류는 없었다. 원자료·화면은 `runs/portone-headless-iiY3Xt/`에 보존했다.

이어 테스트 결제 버튼을 눌러 실제 토스페이 사이트의 QR 인증 화면까지 열었다. 최초 팝업의 about:blank는 렌더링 전 상태였으며, 본문 렌더링을 기다린 재검증에서 토스페이 화면을 확인했다. 네트워크 기록은 Origin과 상태 코드만 남겼으며 인증/결제 토큰이 있는 URL 원문·본문은 보존하지 않는다. 원자료와 화면은 `runs/portone-popup-wait-a1ntT5/`다.

팝업 확인 후 서버 재조회는 HTTP 200·READY·9,000원·TEST·TOSSPAY_V2였고 총 취소액은 0원이었다. 이는 실제 테스트 결제의 시작·채널·서버 조회까지 확인한 결과다. 휴대폰 토스 인증·PAID 승인·취소는 수행하지 않았다. 이후 결제 승인과 실제 취소 확인은 사용자 휴대폰 인증이 필요하다. 업무 앱·DB·보유권·정산 연동은 여전히 후속이다.

검증 서버는 명령 세션 종료에 의존하지 않도록 독립 프로세스로 재기동했으며 `runs/portone-checkout-live-20261027/server.pid`·`server.log`에 실행 정보를 남겼다. 바인딩은 계속 127.0.0.1:18792뿐이다. 같은 run-dir의 결제 번호·취소 시도 근거를 보존한다.

## 2026-10-27 사용자 결제·전액 취소와 서버 최종 확인

사용자가 휴대폰 인증을 거쳐 결제·취소까지 확인했다고 보고한 뒤, Codex가 포트원 서버의 해당 검증 결제를 독립적으로 다시 조회했다. TEST·TOSSPAY_V2·V2·상점/채널 일치·KRW·총액/실제 결제액/총 취소액 모두 9,000원·CANCELLED를 확인했다. `paidAt`은 2026-10-27 05:07:56 KST, `cancelledAt`은 05:08:10 KST다. SUCCEEDED 취소 내역은 1건이며 금액은 9,000원이다.

이미 CANCELLED인 거래로 검증 도구의 취소 요청을 다시 실행해 HTTP 200·기존 CANCELLED 결과를 확인했다. 전후 포트원 취소 내역 ID와 로컬 취소 시도 키가 유지됐다. 이 경로는 기존 완료 결과를 조회해 반환하는 도구의 중복 처리 검증이며, 실제 PG에 서로 다른 키를 보내거나 응답 유실을 주입해 PG 내부 중복 방지를 검증한 것은 아니다.

원자료는 `runs/portone-checkout-live-20261027/provider-final-verification.json`과 `state.json`이다. Secret·개인정보·응답 전체는 보존하지 않았다. 독립 제공사 연결 검증의 첫 단위는 완료했다. Payment 애플리케이션의 승인·환불 포트, Order 환불 결과 반영, License 복구·재구매, Settlement 조정, 웹훅 및 새 업무 흐름의 OCI 인프라 검증은 아직 후속이다.

다음 목표는 Payment의 실제 주문·결제 흐름에 포트원을 연결하는 것이다. 브라우저 완료 알림에만 의존하지 않고 서버 조회로 실제 상태·금액·주문 귀속을 검증한다. 결과를 알아오는 첫 경로(기존 재개 작업을 이용한 주기 조회 우선 또는 웹훅·주기 조회 병행)는 사용자와 선택한 뒤 구현한다. [포트원 웹훅 공식 설명](https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2)과 현재 Payment의 재개 구조를 근거로 비용·지연·누락 복구를 비교한다.

## 2026-10-27 B안: 웹훅 접수·주기 조회 기반

사용자가 웹훅·주기 조회 병행의 B안부터 진행하겠다고 선택했다. 브라우저 완료 응답이 유실돼도 서버가 확인할 경로를 만들고, 서명된 알림도 곧바로 결제 성공으로 신뢰하지 않는다. 사용자가 전달한 별도 웹훅 시크릿은 Git에서 제외한 로컬 설정에만 저장했다. 실제 비밀값은 OCI 소스 묶음이나 테스트 설정으로 전송하지 않았다.

첫 구현 단위는 다음과 같다.

- `PORTONE_TEST` 모드를 명시적으로 선택할 때만 새 어댑터·웹훅 경로가 활성화된다. 기본 SIMULATED 설정과 공유 배포는 변경하지 않았다.
- `POST /api/v1/payments/webhooks/portone`의 원문은 크기를 제한해 읽고, Standard Webhooks의 ID·전달 시각·원문 HMAC-SHA256을 검증한다. 5분 시각 허용 범위·회전 중 복수 서명을 지원하며 중복 헤더·변조·과거/미래 요청을 거절한다. API Secret과 웹훅 시크릿은 다르다.
- `api.listener.PortOneWebhookMapper`는 JSON 중복 키·뒤따르는 추가 JSON·필수 연결값·상점을 검증해 내부 입력으로 변환한다. 알려지지 않은 이벤트 종류는 무시한다.
- 서비스는 로컬 결제를 잠그고 `payment_pg_webhook`의 ID·본문 해시와 재조회 예약을 한 트랜잭션으로 저장한다. 같은 ID·본문은 최초 기록을 유지하고, ID 재사용·다른 본문은 거절한다. 로컬 결제가 없거나 공급자가 다르면 새 업무를 만들지 않는다. 외부 HTTP를 기다리며 DB 잠금을 보유하지 않는다.
- 알림 자체로 PAID·FAILED·환불 완료를 만들지 않는다. 기존 `ResolvePayment`가 조회로 확인한 PAID만 기존 결과·Outbox 경로로 확정한다. 웹훅이 없어도 같은 재개 작업이 조회한다. 공급자별로 대상을 선별해 이전 모의 PG 대기 건이 포트원 건을 가로막거나 다른 공급자를 호출하지 않게 했다.
- 조회 어댑터는 결제 ID·상점·V2·TEST 채널·채널 키·TOSSPAY_V2·KRW·총액/실제 결제액·무할인/미취소·`customData.orderNo`를 대조한다. PG 비용이 없는 응답은 기존 모델의 미확인 NULL로 보존하고 0원을 만들지 않는다.

이번 승인 연결 단위는 PAID만 지원한다. READY·조회 부재·통신 장애·미지원 결과는 미확정으로 유지하며, FAILED의 최종성·재실행 허용·승인 전에 이미 취소된 거래의 반영 등 아직 논의하지 않은 정책을 임의로 확정하지 않았다. PortOne 환불 실행·Order 결과·License 복구와 연결한 것은 아니다. 실제 SDK 시작에는 로컬 Payment의 paymentId와 주문번호를 사용해야 하며, 임의 번호를 쓰는 독립 검증 페이지는 업무 결제 접수 경로를 대신하지 않는다.

### TDD와 OCI 근거

서명 5건·mapper 3건·접수 서비스 5건·조회 어댑터 4건을 추가했다. 동작 미구현의 실패를 확인한 뒤 Green으로 만들었으며, 조회 테스트의 기대 응답 등록 순서 오류는 픽스처 수정으로 구분했다. 원자료는 `runs/payment-portone-tdd-nuclayzz/`다. 최종 로컬 Payment 단위 140건과 통합 소스 컴파일이 통과했다.

사용자가 승인한 `ssh sng`의 `/tmp/stove-portone-webhook-check-i9jZxBxW/work`에 946개 소스를 SHA-256으로 대조하고 Payment 단위 140건·통합 57건을 통과했다. 실패·오류·건너뜀은 0이다. 신규 실제 DB·HTTP 통합 9건은 다음 경계를 검증한다.

- 브라우저보다 먼저 온 알림의 내구 접수와 서버 조회 후 한 번의 승인·Outbox 저장, 중복·뒤늦은 Ready 알림의 원래 결과 보존.
- 웹훅이 없는 경우의 조회 작업, 공급자 장애 뒤 대기·복구, 다른 공급자의 대기 건이 배치를 막지 않는 조건.
- 접수 INSERT 뒤 장애의 전체 롤백·동일 요청 재처리, 동시 동일 알림의 접수 1건.
- 잘못된 서명·오래된 전달·상점·JSON·크기·변경된 ID 재사용·근거 없는 결제와 알 수 없는 이벤트 처리.

외부 포트원 응답은 격리 HTTP 계약 픽스처이며 실제 제공사 웹훅 전달 성공을 검증한 것은 아니다. 기존 모의 PG·JWT·Kafka·환불 접수 회귀도 포함한다. 공통 Kafka의 별도 테스트 태스크는 이번에 실행하지 않았다. 공유 컨테이너 18개와 볼륨 55개가 보존됐고 추가·잔여 Testcontainers가 없었다. 원자료는 `runs/payment-portone-oci-e4wppb57/collected/results/`이며 source 묶음·로그·JUnit·보존 기록을 포함한다.

### 공개 HTTPS 주소 조사

사용자가 기존 주소가 있다고 답한 뒤 Cloudflare CLI·OCI CLI로 직접 확인해 달라고 요청했다. OCI CLI는 인증돼 있으며 구독 리전은 ap-osaka-1이다. 실행 중인 서버 1대에서 80·443 리스너는 없고, 조회된 OCI 로드밸런서·네트워크 로드밸런서는 없다. 기존 ESD 앱은 loopback 포트에 바인딩돼 있다.

로컬 `flarectl`은 설치돼 있지만 `CF_API_TOKEN`/`CF_API_KEY`가 없어 zone 목록 조회가 실패했다. 따라서 Cloudflare 도메인·Workers·Tunnel의 존재 여부를 없다고 단정하지 않는다. 이 조사에서는 리소스·DNS·방화벽·공유 배포를 변경하지 않았다. 실제 웹훅의 공개 주소 연결은 Cloudflare 인증 또는 기존 주소의 확인 뒤 진행한다.

### OCI 공개 IP HTTPS 구성·실제 웹훅 수신

이어 사용자가 OCI 공개 IP 사용을 제안하고 `실행`을 승인했다. 도메인 없이 IP SAN 인증서를 발급할 수 있는 현재 Let's Encrypt 계약을 확인해 `https://161.33.31.159/api/v1/payments/webhooks/portone`을 구성했다. IP 할당은 `EPHEMERAL`이다. 기존 보안 목록·호스트 방화벽의 80·443 허용을 재사용했고 공유 배포를 수정하지 않았다.

전용 nginx `stove-portone-edge`는 80의 ACME 파일과 443의 정확한 웹훅 POST만 공개한다. Certbot 5.8.0의 `shortlived`·webroot로 공개 신뢰 IP 인증서를 발급했고 TLS 1.3·IP SAN·신뢰 검증을 통과했다. UTC 00·08·16시의 갱신 타이머를 활성화했으며, 갱신 dry-run 성공·systemd 서비스 성공·nginx reload를 확인했다. 설정 원문과 운영 범위는 [edge 기록](../tools/verification/support/portone-edge/README.md)을 따른다.

코드·서비스 실행을 로컬에 유지하고 실제 API Secret·웹훅 시크릿을 OCI에 복사하지 않기 위해, OCI loopback 18883에서 로컬 Payment 18783으로 SSH 역방향 연결을 사용했다. DB는 전용 OCI MySQL 13383이며 로컬로 정방향 연결한다. Kafka 리스너·Outbox 전송은 끈 합성 주문 픽스처이고, 실제 포트원 조회 작업은 켰다. Mac의 Payment·SSH 연결이 꺼지면 업무 웹훅 수신도 중단되는 검증 환경이다.

SDK `noticeUrls`로 이 주소를 지정한 새 TEST 결제창을 헤드리스에서 열어 실제 `Transaction.Ready`를 수신했다. 포트원 조회에서 `SUCCEEDED`·HTTP 200과 DB 접수를 대조했고, 제공사 재발송 API로 같은 웹훅을 다시 받아 최초 접수 시각·본문·1개 기록을 보존했다. 포트원 조회에는 서로 다른 요청 시각의 성공 전송 2건이 남았다. 새 거래는 `READY`, Payment는 `PENDING`이다. 결제 개시를 성공으로 바꾸지 않았으며 새 결제 승인·취소는 수행하지 않았다. 콘솔 기본 주소를 바꾸거나 실제 Order 업무의 SDK 연결을 완료한 것은 아니다.

공개 HTTP 7건은 정상 서명의 알 수 없는 타입 200, 변조·무서명 401, 64 KiB 초과 413, 웹훅 GET 405, 다른 업무 경로·루트 404를 확인했다. 로컬 bootJar도 통과했다. 공유 컨테이너 18개·기존 볼륨 55개를 보존했고 전용 nginx·검증 MySQL 두 개만 추가했으며 추가 Docker 볼륨은 없다. 원자료는 `runs/portone-public-https-cm9y22_z/`의 인증서·갱신·HTTP·제공사 상태·DB 접수·보존 기록과 `resume-local.py`다. 이 설정 작업 뒤 기존 Payment 단위 140·통합 57건을 재실행했다고 기록하지 않는다.

## 2026-10-27 A안: 본인 결제창 정보 전용 API

사용자가 결제창 정보 전용 API의 A안을 선택했다. 기존 Payment 조회와 화면의 별도 설정을 조립하는 B안보다 API·테스트 비용이 추가되지만, 결제창의 공개 설정과 기존 주문·결제 연결값을 서버에서 모아 제공하기로 했다. 금액 변조 방지는 기존의 최종 PG 조회·금액/상점/채널/주문 대조가 담당한다.

### API와 책임

`GET /api/v1/payments/{orderNo}/checkout`은 `PORTONE_TEST` 설정에서 제공한다. MEMBER JWT의 sub로 기존 내 결제를 조회하며, 소유한 결제가 로컬 PENDING이고 공급자가 PORTONE_TEST인 경우에만 반환한다. 응답은 `ApiResponse.data`와 `Cache-Control: no-store`를 사용한다.

```json
{
  "success": true,
  "data": {
    "storeId": "store-...",
    "channelKey": "channel-key-...",
    "paymentId": "기존 Payment의 고정 paymentId",
    "orderName": "주문 시점 상품명",
    "totalAmount": 9000,
    "currency": "CURRENCY_KRW",
    "payMethod": "EASY_PAY",
    "customData": {"orderNo": "기존 주문번호"},
    "noticeUrls": ["https://161.33.31.159/api/v1/payments/webhooks/portone"]
  }
}
```

금액은 기존 주문 스냅샷의 할인을 반영한 결제액이다. 요청에서 금액·새 결제번호·채널·웹훅 주소를 받지 않는다. API Secret·웹훅 시크릿은 응답 모델에 전달하지 않는다. 응답의 data를 브라우저 SDK의 requestPayment에 전달하는 계약이며, 실제 구매 화면 자체를 추가한 것은 아니다.

- 인증 없음·잘못된 JWT는 401, MEMBER 역할 없음은 403이다.
- 아직 접수되지 않았거나 존재하지 않는 결제와 타인 결제는 기존 내 결제 조회와 같은 404다. 이 API가 다른 모듈을 조회하거나 임시 결제를 만들지 않는다. 화면은 이미 확인한 자기 주문과 연결해야 하며, 404 하나만으로 모든 요청을 준비 중으로 해석하지 않는다. 대기 안내·조회 시간 제한 정책은 별도 쟁점이다.
- 로컬 PAID·FAILED와 다른 공급자의 결제는 409다. 실제 PG의 FAILED를 로컬 최종 실패로 분류하는 정책을 새로 정한 것은 아니다.
- GET 시점의 PENDING 확인은 결제 예약이나 PG 실행이 아니다. 응답 후 상태가 달라질 수 있고, 이전 응답을 가진 브라우저의 호출을 취소하지 않는다. 고정 paymentId·제공사의 중복 결제 방지와 서버의 최종 조회를 계속 사용한다.

`ReadPaymentCheckout`은 기존 본인 조회를 재사용하며 DB 변경·PG 호출을 하지 않는다. 내부 조회 결과를 API DTO가 SDK 형식으로 바꾼다. 공개 설정도 config에서 개별 값으로 주입하며 바인딩 객체를 서비스에 넘기지 않는다. `PORTONE_WEBHOOK_URL`은 HTTPS 주소로 명시하고 사용자 입력이나 요청 Host에서 만들지 않는다. 현재 저장된 공개 IP 주소를 로컬 Git 제외 설정에 추가했다. 비밀값은 재입력하거나 OCI 소스에 복사하지 않았다.

### TDD와 검증

서비스 5건은 미구현 예외로 실제 Red를 확인했다. 응답·설정 3건 중 SDK 응답 미구현과 누락된 웹훅 URL 검증의 Red 2건도 확인했다. 구현 후 로컬 Payment 단위 148건·통합 소스 컴파일·bootJar가 통과했다. 단위 Red·Green 원자료는 `runs/payment-checkout-tdd-p2__qvdo/`다.

OCI HTTP Red 3건은 읽기 API가 아직 연결되지 않아 정상 구매자에게도 403을 반환한 실제 실패다. 실제 JWT/JWKS·MySQL·Spring HTTP로 공개 응답·권한·주문 귀속·반복 조회·읽기 불변성·종료/다른 공급자 거절과 웹훅 이후 PAID 확정을 검증한다. 실제 포트원 승인이나 Order부터 연결한 E2E와 구분한다. 최종 OCI Green은 단위 148건·통합 60건(신규 HTTP 3건)이며 실패·오류·건너뜀은 0이다. 소스 959개를 SHA-256 대조했다. 공유 컨테이너 20개(앞 단계에서 만든 edge·검증 DB 포함)와 기존 볼륨 55개를 보존했고 잔여 Testcontainers도 없다. 원격 격리 경로는 `/tmp/stove-payment-checkout-check-PN6pUje2/work`, 원자료는 `runs/payment-checkout-oci-zoy5astj/collected/results/`의 Red/Green 결과·소스·로그·JUnit·보존 기록이다. 공통 Kafka의 별도 단위 태스크는 이번에도 실행하지 않았다.


검증용 로컬 Payment도 새 빌드로 재기동해 새 API의 OpenAPI 등록·미인증 401·health UP을 확인했다. 실제 포트원 준비 웹훅 재발송은 다시 SUCCEEDED·HTTP 200이며 접수는 최초 1건·PENDING을 유지했다. 초기 재기동 때 실행 중인 빌드 JAR를 덮어써 구 프로세스 종료 중 클래스 로딩 오류가 발생했고, 해시 이름의 별도 runtime JAR 복사본으로 전환해 재발 원인을 제거했다. 현재 프로세스는 해당 복사본으로 실행하며 원자료는 단위 검증 디렉터리의 live-activation.json·runtime-artifact.json이다.

## 2026-10-27 실제 Order·Payment·포트원 승인 연결 검증

사용자가 다음 단계를 지시해 실제 회원·주문·결제 생성과 공개 웹훅을 한 검증 환경으로 연결했다. 업무 코드는 변경하지 않았고, 이미 검증한 동일 Payment 빌드와 최신 완료 Auth·Catalog·Order의 로컬 bootJar를 사용한다.

- 실제 Auth 회원가입과 Authorization Code + PKCE로 MEMBER JWT를 발급했다. 검증 클라이언트는 토큰을 메모리에서만 쓰며 브라우저·로그에 노출하지 않는다. 전용 계정 자격은 Git 제외 실행 디렉터리의 600 권한 파일에 있다.
- Studio의 출시 요청만 기존 `ProjectReleaseRequestedEvent` 계약 픽스처로 Kafka에 발행했다. 실제 Catalog의 입력 검증·공개 확정·HTTP 조회가 수행됐으며 별도 DB에 공개 상품 1개가 저장됐다. 실제 Studio·Review 출시 업무를 수행했다고 표시하지 않는다.
- 실제 Order HTTP API가 주문을 저장하고 OrderPlaced Outbox를 SENT로 발행했다. 실제 Kafka 소비로 Payment가 결제를 생성했고, 전용 checkout API가 고정 결제번호·9,000원·주문 연결값을 반환했다. 주문·결제를 SQL로 직접 생성하지 않았다.
- 전용 API의 응답으로 헤드리스 토스페이 TEST 결제창을 열었다. 실제 포트원 READY 응답의 customData는 문자열이며 기존 주문번호와 일치했다. 실제 Transaction.Ready 웹훅은 공개 HTTPS에서 수신해 제공사 SUCCEEDED·HTTP 200을 확인했다.

초기에는 QR을 제공하고 사용자 휴대폰 승인을 기다렸다. 사용자의 승인 보고 뒤에도 포트원이 READY여서 열린 결제창을 확인했고, 토스 공식 가맹점 테스트 페이지의 마지막 이동하기 버튼이 남아 있음을 확인했다. 제공된 버튼을 그대로 클릭한 뒤 실제 포트원 PAID·paidAt·pgTxId와 Payment·Order PAID를 독립 조회·DB로 대조했다. 결제 시각은 2026-10-27 08:54:13 KST, Payment 결과 저장 시각은 08:54:16.205274 KST다. TEST·TOSSPAY_V2·상점/채널/주문 귀속·KRW·결제액 9,000원·할인/취소 0원을 확인했다. 검증 화면은 완료를 표시하며 환불 실행은 제공하지 않는다. 기존 독립 취소 도구로 이 업무 결제를 직접 취소하지 않는다.

원자료와 재개 경로는 `runs/portone-order-live-j2flh033/`다. 현재 주문은 `b3b36f62-625f-44d9-a444-23450b97e3c6`, 결제는 `ce01f6e7-f3f5-4934-a169-a64e9983ab94`, 금액은 9,000 KRW다. 재개·재확인 때 같은 주문·결제를 유지한다. `prepare-order.py`도 최초 requestId를 보존한다. 브라우저 대기 시간과 도구의 조회 제한은 검증 도구의 한계이며 고객 주문의 만료·취소·실패 정책을 정한 것이 아니다.

로컬 Auth 18891·Catalog 18881·Order 18882·Payment 18783, 검증 화면 18795를 사용한다. 전용 SSH는 DB 13384·Kafka 19492를 연결하고, 별도 역방향 SSH가 OCI loopback 18883을 로컬 Payment 18783으로 연결한다. 공개 HTTPS 주소·인증서 갱신 설정은 앞 단계와 같다. API·웹훅 비밀값은 계속 로컬에만 있다.

OCI 격리 경로는 `/tmp/stove-portone-order-check-kdxtkug4`이며 전용 MySQL·Kafka 컨테이너를 추가했다. 이전 검증 DB와 공유 배포는 보존한다. 앞 단계의 로컬 Payment·SSH는 새 실제 주문 환경으로 전환하기 위해 중지했으며, 현재 재개 도구는 새 실행 디렉터리의 `resume-stack.py`다. 구 `resume-local.py`를 함께 실행하지 않는다. 각 앱은 빌드 출력과 분리한 해시 이름의 runtime JAR를 실행한다.

공유 자원 대조 결과 기존 컨테이너 20개·볼륨 55개와 ID/시작/재시작/건강 상태를 보존했다. 새 전용 MySQL·Kafka 두 개와 해당 컨테이너에 연결된 Docker 볼륨 두 개를 추가했다. 소스 959개와 실행 JAR 해시·준비 상태·기존/추가 자원은 실행 디렉터리의 source-manifest.json·artifacts.json·validation-summary.json·preservation.json·owned-mounts.json에 기록했다. 실제 PG 비밀값의 소스 포함 여부도 검사해 0건이었다. 후속 사용자 승인과 테스트 페이지의 결과 반환 뒤 실제 주문·결제 성공 연결을 완료했다. 전체 상품 출시·라이선스 지급·정산·환불 완료와 구분한다.

결제 완료 웹훅도 실제 제공사 API로 재발송했다. 전송은 SUCCEEDED·HTTP 200이고 재발송 전후 주문/결제의 확정 결과 ID·시각·구매자·상품·금액·요청 연결이 같았다. 웹훅 저장은 Ready/Paid 2개 그대로이며 OrderPaymentCompleted·OrderPaid·OrderPlaced Outbox는 각각 SENT 1개다. 본인 checkout API도 실제 PAID 이후 409로 거절됐다. PG 비용은 모르는 값인 null을 유지했다.

최종 근거는 provider-paid-verification.json·user-approval-verification.json·after-approval-db.tsv·confirmed-facts-before/after-replay.json·paid-webhook-replay.json·checkout-after-paid.json·preservation-final.json·validation-summary.json이다. prepare-order.py·open-checkout.cjs를 이 PAID 거래에서 다시 실행해 새 결제창을 만들지 않는다. 현재 상태 재확인은 검증 화면 127.0.0.1:18795 또는 기존 내 주문·내 결제 조회를 사용한다. 환불 승인·조회·멱등과 보유권/정산 후속 계약은 아직 구현 완료가 아니다.

## 2026-10-27 장기 미확정 환불의 자동 재전송 A안

사용자는 시도별 PG 멱등 키 B안을 유지하면서, 이번에는 보장 기간이 지난 뒤 취소 명령 자동 재전송을 중단하는 A안을 선택했다. 현재 포트원 공식 멱등 보장은 3시간이다. 기간 이후에는 결과 조회를 허용하고 운영 확인 대상으로 다루며, 경과 시간만으로 환불 실패·보유권 복구·새 시도를 만들지 않는다. B안의 조건부 장기 재전송은 선택하지 않았다.

### 구현한 준비 경계

PG 환불 실행기는 아직 없으므로 `RecordRefundCancellationPreparation`에 요청 직전의 DB 준비 경계를 구현했다. 이 서비스는 실제 취소·조회 HTTP를 호출하지 않는다. 후속 PG 실행기·성공 근거·실패 분류를 이 단계에서 임의로 확정하지 않았다.

- 기존 attemptId 행을 비관적 잠금으로 읽고 최초 준비 시각 `first_cancel_prepared_at`을 저장한다. 짧은 트랜잭션이 커밋된 뒤에만 불변 `RefundCancellationPermission`을 반환한다. 외부 트랜잭션 안의 호출은 거절해 아직 커밋되지 않은 준비로 PG를 호출하는 경로를 막는다.
- 접수 시각이나 주문 결제 시각 대신 최초 송신 준비 시각부터 3시간을 계산한다. 실제 PG 수신 시각과 DB 기록을 원자적으로 저장할 수 없어, HTTP 전에 커밋하는 보수적인 기준이다. 준비 직후 프로세스가 종료돼 HTTP가 나가지 않아도 시간은 진행될 수 있으며 장기 장애 때 운영 확인 비용이 남는다.
- 같은 시도의 재준비·프로세스 재시작·원래 실행 요청의 중복 접수는 최초 준비 시각·attemptId·원환불 입력·기한을 갱신하지 않는다. PG HTTP 본문의 세부 매핑은 후속 계약이다.
- 정확히 3시간이 되는 순간부터 재전송 권한을 거절한다. 마지막 1마이크로초까지의 경계를 검사한다. `auto_cancel_stopped_at`은 기한 경과가 관측되었을 때 저장하고 최초 값을 보존한다. 한 번 저장한 중단은 뒤늦은 시각 역행으로 풀리지 않는다.
- 예전에 받은 준비 결과도 `requireCancellationAllowedAt`으로 송신 직전 시각을 다시 검사해야 한다. 후속 HTTP 어댑터는 이 검사를 생략하면 안 된다. DB 잠금을 잡은 채 HTTP를 기다리지 않는다.
- 기간이 지나도 PENDING과 원래 조회용 연결값을 유지하고 `requiresOperatorReviewAt`으로 운영 확인 필요를 판별한다. 조회 작업자·운영 API/화면·알림 전송·최종 환불 결과 이벤트는 아직 후속이다. 여기서 조회 반복 작업이 이미 실행된다고 표시하지 않는다.

V16은 nullable DATETIME(6) 두 필드와 시간 관계의 MySQL CHECK를 추가한다. V15까지의 접수는 PG 실행 전 단계였으므로 기존 PENDING 행의 최초 준비 시각을 추정해 채우지 않는다. 준비·중단 시각 모두 NULL로 보존한다.

### TDD와 검증

도메인 6건·서비스 4건은 미구현으로 실패한 Red를 실제 확인했다. 서비스 이름은 모듈의 동사 규칙에 맞춰 `RecordRefundCancellationPreparation`으로 정리했다. 구현 후 로컬 Payment 단위 158건·통합 소스 컴파일·bootJar가 통과했다. 단위 원자료는 `runs/refund-window-tdd-u8jdb08z/`다.

OCI 격리 `/tmp/stove-refund-window-check-hjlfQvVR/work`에서 소스 966개를 SHA-256 대조했다. 첫 전체 실행은 단위 158건 통과, 통합 65건 중 신규 2건이 실패했다. Repository 인터페이스 spy에서 추상 메서드를 실제 호출한 장애 주입과 MySQL CHECK의 실제 SQL 오류 3819/HY000을 다른 Spring 예외 타입으로 예상한 픽스처 문제였다. 운영 구현은 그대로 두고 실제 EntityManager.flush 이후 장애를 주입하며 제약 이름·벤더 오류 번호를 검사하도록 수정했다. 최종 통합 전체 65건은 실패·오류·건너뜀 0으로 통과했다. 단위 158건의 근거는 앞 full 실행이며, corrected 실행에서 단위 태스크를 다시 실행했다고 표시하지 않는다. 두 실행의 운영 소스·단위 소스는 동일하고 통합 픽스처 한 파일만 수정됐다. 공유 컨테이너 22개·볼륨 57개를 보존했고 추가·잔여 Testcontainers가 없다. 원자료의 collected/results/full·corrected에 각 실행 명령·소스·로그·JUnit이 있으며 preservation.json에 보존 근거가 있다. 원자료는 `runs/refund-window-oci-22cus_i6/`다.

검증된 Payment JAR를 별도 해시 이름의 runtime 복사본으로 로컬 실제 주문 환경에 반영했다. 건강검사는 UP이고 V16 적용 성공, 기존 Order·Payment의 PAID 각 1건과 refund_execution 0건을 확인했다. 실제 PG 환불 요청은 보내지 않았다. 근거는 `runs/refund-window-tdd-u8jdb08z/live-activation.json`과 `live-guard-runtime.json`이다.

## 확인한 공식 자료

- [토스페이먼츠 테스트 키·키 종류](https://docs.tosspayments.com/reference/using-api/api-keys): 테스트 키와 라이브 키·결제 제품별 키를 구분한다.
- [토스페이먼츠 결제 연동 방식](https://docs.tosspayments.com/guides/v2/payment-widget): 제공하는 UI·SDK 연동 방식을 확인한다.
- [포트원 V2 연동 준비](https://developers.portone.io/opi/ko/integration/ready/readme?v=v2): 테스트 채널·상점 ID·V2 Secret 준비를 확인한다.
- [포트원 V2 API](https://developers.portone.io/api/rest-v2): 멱등 키의 형식·현재 보장 기간과 결과 조회를 확인한다.
- [포트원 V2 취소](https://developers.portone.io/opi/ko/integration/cancel/v2/readme?v=v2): 전액 취소와 선택한 PG·결제수단의 제약을 확인한다.
- [포트원 V2 토스페이](https://developers.portone.io/opi/ko/integration/pg/v2/tosspay-v2): 현재 사진의 `tosspay_v2` 채널은 토스페이 간편결제이며 `EASY_PAY`를 사용한다.
- [Let's Encrypt IP 인증서](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability): 공개 IP SAN·160시간 인증서 지원을 확인한다.
- [Certbot IP 인증서 설정](https://letsencrypt.org/2026/03/11/shorter-certs-certbot.html): webroot·갱신과 서버 인증서 reload 필요성을 확인한다.

과거 GitHub #82의 포트원 계획은 `not_planned`로 닫혀 있다. 이번 대상은 사용자가 새로 제공한 테스트 채널을 근거로 하며, 과거 계획이나 제거된 구현을 호환성 요구로 사용하지 않는다.
