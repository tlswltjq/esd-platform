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


## 2026-10-27 전액 취소 조회 확인 A안

사용자가 A안을 선택했다. 결제의 전액 취소라는 금융 사실과 우리 실행 시도의 성공 여부는 별개다. 고객 환불은 전액 취소가 증명되면 완료하고 실행 귀속 불명은 별도 조사 기록으로 보존한다. 원래 결제·주문 PAID와 최초 결제 결과를 덮어쓰지 않는다.

### 계약과 구현 경계

- Payment core.port의 RefundPaymentLookup은 읽기 전용이며 취소 HTTP POST를 하지 않는다. PortOneRefundPaymentLookup은 GET /payments/{id}로 CANCELLED·상점·V2·TEST/TOSSPAY_V2·채널·주문 customData·원 pgTxId/paidAt·KRW·total/paid/cancelled 전액·discount 0을 대조한다. 원결제는 이미 로컬 PAID로 검증·저장된 결제여야 한다.
- 성공 취소의 ID가 중복되지 않고 금액이 양수이며 합계가 원결제액과 같아야 한다. 여러 취소가 합쳐 전액인 경우도 인정한다. REQUESTED·알 수 없는 상태·합계 부족·불일치는 완료하지 않는다. FAILED 내역은 성공 합계에 넣지 않으며 그것만으로 현재 시도의 확정 실패를 만들지 않는다. 다른 결제 상태와 통신 장애도 완료·실패로 바꾸지 않는다.
- 공식 스키마상 개별 취소의 cancelledAt은 선택 항목이다. 누락 시 해당 시각을 추정하지 않고 NULL로 보존한다. 결제 CANCELLED의 필수 최종 cancelledAt은 따로 저장하고 알려진 개별 시각과 원결제 시각을 대조한다.
- ReconcileRefundPayment는 트랜잭션 밖에서 조회한 후 짧은 트랜잭션에서 Payment → 기존 attemptId 순으로 잠근다. 기존 요청과 원결제를 다시 대조하고 최초 확인·성공 취소 근거·RefundPaymentConfirmed Outbox를 원자적으로 저장한다. 별도 refundConfirmedAt이 있으면 재조회·재전송 준비를 멈춘다. 기존 실행 PENDING은 성공 여부 불명을 뜻하며 조회로 SUCCEEDED를 발명하지 않는다. 원결제당 새 실행 차단도 유지한다.
- Payment V17은 확인 메타데이터와 취소 근거 컬렉션 테이블을 추가한다. 근거는 같은 실행과 취소 ID별로 유일하며 기존 접수에 확인 시각·결과를 채우지 않는다. 이 단계에서는 제공사별 요청·승인 성공 응답을 매핑하지 않는다.
- 새 RefundPaymentConfirmed는 Payment 토픽·orderNo 키를 사용한다. eventId/시각·refund/attempt·원 실행 요청 이벤트·주문·결제·구매자·금액·통화·PG_QUERY_UNATTRIBUTED·원 PG 거래·취소 ID들을 전달한다. ‘우리 실행 성공’ 이벤트가 아니다.
- Order의 전용 mapper는 봉투·본문·키 및 내부 입력 자체를 검증한다. ApplyRefundPaymentConfirmation은 주문 잠금 아래 현재 환불·시도·원래 요청·구매 사실을 대조하고 COMPLETED·최초 근거 지문·Inbox·실행의 reconciliationRequired를 함께 저장한다. 실행 PENDING·resolvedAt NULL은 유지한다. 같은 이벤트의 같은 내용만 멱등 처리하며 변경된 내용은 격리한다. DB 일시 실패는 재시도하고 DLT 발행 실패는 원본을 완료하지 않는 기존 환불 소비 정책을 사용한다.
- Order V16은 완료 근거와 조사 표시만 추가한다. 정지 완료가 늦게 재전달돼도 이미 소비한 동일 정지 근거는 새 실행을 만들지 않는다. 보유권 복구·재구매·정산 조정은 이 단계에서 구현하지 않는다.

조회 조정 서비스·이벤트·Order 완료까지의 경로를 구현한다. 자동 조회 스케줄 등록/주기·실제 취소 송신기·운영 조사 UI/알림·고객/관리자 환불 API는 후속이다. 합의하지 않은 실행 간격·실패 분류를 테스트로 먼저 고정하지 않는다. 실제 PG HTTP 대신 계약 픽스처를 쓴 통합 검증과 실제 TEST 취소를 구분한다.

### TDD와 검증

Payment 도메인·읽기 어댑터 Red 9건과 Order 도메인 Red 3건을 확인했다. 초기 Payment 테스트는 기존 SIMULATED 성공 픽스처를 PORTONE_TEST 결제에 사용한 설정 오류가 있어 먼저 고쳤고, corrected Red의 미구현 실패를 근거로 한다. 단위·OCI 최종 결과와 공유 자원 보존 기록은 검증이 끝난 뒤 기록한다. 원자료는 runs/refund-confirmation-tdd-7pavkmo2다.

첫 OCI full 실행은 공통 Kafka 187·Payment 172·Order 247 단위가 통과했고 Payment 통합 69건 중 2건·Order 통합 75건 중 1건이 실패했다. 동시 조회는 Hibernate PersistentBag의 equals가 목록 내용 동등성을 제공하지 않아 같은 근거를 변경된 근거로 거절한 운영 코드 결함이었다. 불변 List 복사본의 내용으로 비교하도록 수정했다. 나머지 두 실패는 OutboxRecorder/ProcessedEventGuard의 MANDATORY 트랜잭션 조언을 받는 spy에 트랜잭션 밖에서 장애를 설정한 픽스처 문제였다. 설정을 실제 짧은 트랜잭션 안에서 수행하도록 고쳤다. 수정한 운영 파일 1개·통합 파일 2개를 포함한 전체 소스를 다시 해시 대조하고 --rerun-tasks로 같은 전체 범위를 재검증한다. 첫 실패·수정 후 실행을 별도 결과로 보존한다.

최종 corrected 실행은 --rerun-tasks로 공통 Kafka 단위 187·Payment 단위 172·Order 단위 247·Payment 통합 69·Order 통합 75건을 모두 실패·오류·건너뜀 0으로 통과했다. 단위 합계 606·통합 합계 144건이다. 신규 통합은 Payment 4건과 Order 7건이며, 실제 DB의 근거/Outbox·완료/Inbox 롤백·동시 처리, Kafka 중복/offset·일시 실패 재시도·격리·DLT 발행 실패 시 미커밋을 검증한다. OCI 소스 986개를 대조했고 공유 컨테이너 22개·볼륨 57개·ID/시작/재시작/건강 상태를 보존했다. 추가·잔여 Testcontainers와 볼륨은 없다. 원자료 full·corrected 명령/소스/로그/JUnit·보존 기록은 runs/refund-confirmation-oci-5naahx04/collected/results, 원격 격리는 /tmp/stove-refund-confirmation-check-E5y3zflx/work이다. 현재 운영·테스트 소스는 corrected 해시와 같으며 검증 이후에는 문서만 갱신했다. 로컬 bootJar와 통합 소스 컴파일도 통과했다.

검증된 해시 이름 runtime JAR로 로컬 실제 주문 환경의 Order·Payment를 재개했다. 두 건강검사는 UP이고 Order V16·Payment V17 적용 로그를 확인했다. 반영 전후 본인 주문/결제 조회 응답 전체가 동일하며 기존 9,000원 결제는 PAID다. 실제 PG 취소 POST는 보내지 않았다. 근거는 runs/refund-confirmation-tdd-7pavkmo2/live-before-activation.json·live-activation.json·activation.log다.


## 2026-10-27 원결제 전액 잔액 조건 A안

사용자는 자동 처리를 최대한 명확하고 간단한 경우에만 적용하는 것이 자동화의 의의에 맞다고 판단해 A안을 선택했다. 원래 결제액이 전부 남아 있는 경우에만 자동 취소한다. 외부에서 일부가 이미 취소됐다면 잔액을 계산해 추가 취소하는 B안은 구현하지 않는다. 이 때문에 고객의 추가 대기·운영 확인 비용이 남는다. 이미 전액 취소된 금융 사실을 조회로 검증하면 앞서 합의한 대로 고객 환불 업무를 완료한다.

- PG 요청은 원결제액을 amount와 currentCancellableAmount 양쪽에 명시한다. 상점·원래 paymentId·attemptId도 고정한다. reason은 개인정보 없이 refundId를 넣은 기술적 상관값이며 취소 주체나 시도 성공의 증거로 쓰지 않는다. 선택 항목 requester 등 아직 합의하지 않은 필드는 보내지 않는다.
- 원 PAID의 PG 거래·시각·상점·V2·TEST/TOSSPAY_V2·채널·주문·통화·원액/순결제액·할인 0·취소액 0과 미처리 취소 이력 부재를 HTTP 조회로 대조한다. 현재 취소액이 0보다 크고 원액보다 작으면 자동 송신을 중단한다. 다른 상태·취소 이력·잘못된 응답은 단순 자동 송신 대상이 아니다.
- 조회 직후 외부 부분 취소가 끼어드는 반례는 PG의 예상 잔액 조건으로 추가 방어한다. 명시적인 CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN 409만 잔액 변화로 관측한다. 일반 409·IDEMPOTENCY_OUTSTANDING_REQUEST·통신 장애를 확정 실패나 잔액 변화로 치환하지 않는다. 이 조건만으로 3시간 밖의 멱등이나 모든 PG 내부 경합의 원자성이 증명됐다고 하지 않는다.
- RequestRefundCancellation은 Payment→기존 실행 잠금 아래 최초 준비 시각과 실제 보낼 JSON 본문을 함께 커밋한 뒤 잠금 밖에서 외부 호출한다. 첫 본문을 그대로 저장·재사용하므로 배포 뒤 reason/직렬화 생성 방식이 달라도 기존 키의 본문을 바꾸지 않는다. 저장된 본문은 송신 어댑터에서 원 요청·상점·두 금액·허용 필드와 다시 대조하며 UTF-8 원문으로 보낸다. API Secret은 본문·DB 스냅샷에 넣지 않는다.
- PortOneRefundCancellationGateway는 UUID attemptId를 RFC 8941 문자열로 감싼 Idempotency-Key를 보낸다. 3시간 전송 기한은 사전 조회 시작과 POST 직전에 모두 검사한다. 네트워크 지연 후 PG 도착·처리 시각을 로컬 시각으로 확정하는 것은 아니며, 기한만으로 금융 실패를 만들지 않는다.
- 부분 취소·명시적 잔액 불일치는 V18의 cancellation_balance_changed_at으로 최초 관측을 보존한다. 해당 실행은 이후 자동 POST를 재개하지 않는다. 기존 3시간 중단 필드는 기한 경과에만 사용한다. PENDING·보유권 정지는 유지하며 새 시도나 복구를 만들지 않는다. 중단 뒤에도 전액 취소 조회 확인은 가능하다.
- HTTP 2xx만으로 우리 실행의 성공을 기록하지 않는다. 현재 단계에서는 결과 미확정을 보존하고 뒤이은 ReconcileRefundPayment의 독립 조회로 금융 사실만 확인한다. POST 후 통신 결과가 미확정이어도 조회가 전액 취소를 증명하면 기존 완료 경로를 사용한다. 취소 성공 응답의 귀속·확정 실패 분류·복구는 후속이다.

새 송신 서비스는 자동 스케줄·외부 환불 API에서 아직 호출하지 않는다. 기존 9,000원 실제 TEST 결제의 보유권 지급/정지와 환불 권한을 우회해 취소하지 않는다. 실제 PG 자금 동작과 HTTP 계약 픽스처 검증을 구분한다. 단위 Red는 요청 어댑터 8·도메인 3·서비스 4건이며 원자료는 runs/refund-balance-tdd-t3jgf86j다. OCI 최종 결과는 아래 기록을 따른다.

최종 OCI full 실행에서 Payment 단위 187건·통합 77건이 실패·오류·건너뜀 0으로 통과했다. 신규 통합 8건은 실제 DB 커밋의 외부 관측·HTTP 본문/키·선행 부분 취소·조회/POST 사이 잔액 변화·처리 뒤 결과 미확정과 조회 복구·배포/재시작의 최초 본문 보존·3시간 기한·실제 flush 후 롤백·동시 처리/Outbox 1건을 검증했다. HTTP 공급자는 격리 계약 픽스처이며 실제 포트원의 경합 원자성이나 실제 자금 이동을 검증한 것으로 표시하지 않는다. 소스 996개를 SHA-256 대조했다. 공유 컨테이너 22개·볼륨 57개와 상태를 보존했고 추가·잔여 Testcontainers/볼륨이 없다. 원자료는 runs/refund-balance-oci-gzscdzop/collected/results/full 및 preservation.json이다. 로컬 단위·통합 컴파일·bootJar도 통과했다. 이번 실행은 Payment만 재검증했으며 이전 단계의 Order 247·공통 Kafka 187 단위와 Order 75 통합을 다시 실행한 것으로 합치지 않는다.

검증된 해시 이름 runtime JAR로 로컬 Payment만 재개해 건강검사 UP·V18 적용을 확인했다. Auth·Catalog·Order·SSH는 재시작하지 않았다. 반영 전후 본인 주문/결제 응답 전체가 동일하고 기존 9,000원 주문·결제는 PAID다. 실제 PG 취소 POST는 보내지 않았다. 근거는 runs/refund-balance-tdd-t3jgf86j/live-before-activation.json·live-activation.json·activation.log다.


## 2026-10-27 검증된 취소 완료 응답 A안

사용자는 정상적인 취소 완료 응답을 검증하면 후속 결제 조회의 성공을 기다리지 않고 우리 시도 성공과 고객 환불 완료를 확정하는 A안을 선택했다. HTTP 2xx 자체가 근거가 아니며 SUCCEEDED·취소 ID·원액·원래 요청의 연결·필수 필드와 시각을 검증한 응답만 해당한다. 응답을 잃었거나 불완전하면 기존 조회 확인 경로를 사용한다. 성공 응답과 추후 조회가 모순되는 경우는 최초 결과를 덮어쓰지 않고 별도 대사가 필요한 비용이 남는다.

- PortOne 송신 어댑터는 기존 키·본문·전액 잔액 조건을 유지하고, 성공 본문을 PgRefundCancellationSuccess로 변환한다. 요청 경로/멱등 키/동결 본문에 연결된 원결제·attemptId와 정확한 전액, 취소 ID·taxFreeAmount/vatAmount·reason·requestedAt을 검증한다. 선택인 cancelledAt은 없으면 NULL로 보존한다. REQUESTED·FAILED·모르는 상태·불완전/불일치 본문은 이 단계에서 확정 실패로 바꾸지 않고 UNCONFIRMED로 둔다. 실패 분류·복구는 후속이다.
- RecordRefundCancellationSuccess가 짧은 자체 트랜잭션에서 Payment→실행을 잠그고 원 요청·불변 결제·직접 응답의 연결을 재확인한다. 성공 관측·필수 응답 근거·시도 SUCCEEDED·금융 완료와 Outbox를 함께 저장한다. HTTP 성공 뒤 DB 저장이 실패하면 모두 롤백되며, 이를 PG 실행 실패로 바꾸지 않는다. 같은 근거를 보유한 호출자는 저장을 재개할 수 있고 근거 자체를 잃으면 조회 완료/귀속 불명 경로가 남는다.
- 정상 응답이 금융 사실의 첫 근거라면 source=PG_RESPONSE의 RefundPaymentConfirmed 한 건이 고객 완료와 시도 성공을 함께 나타낸다. 금융 완료 ID와 성공 ID는 이 한 이벤트의 ID를 함께 사용한다. 추가 GET을 필수로 호출하지 않는다. 응답이 증명하지 않은 PG 취소 시각을 로컬 관측 시각으로 추정해 채우지 않는다.
- 다른 조회 작업이 먼저 전액 취소를 확정했다면 최초 금융 ID·시각·source·근거는 유지한다. 직접 응답의 취소 ID/금액이 그 근거와 같아야 시도만 성공으로 보완하며, 최초 금융 이벤트를 참조하는 별도 RefundExecutionSucceeded를 한 번 저장한다. 다른 취소 ID의 성공 주장으로 기존 금융 기록이나 실행 결과를 덮어쓰지 않는다.
- Order의 mapper는 두 이벤트의 봉투·본문·키·내부 값을 검증한다. PG_RESPONSE 완료는 현재 주문·환불·시도·원래 요청을 대조하고 완료·시도 SUCCEEDED·조사 표시 해제·Inbox를 원자적으로 반영한다. 늦은 성공 이벤트는 시도 성공만 보완하며 최초 금융 완료를 바꾸지 않는다. 성공 보완 이벤트가 먼저 도착해도 원래 금융 이벤트의 ID·시각으로 최종 완료한다. 이는 이미 내구 저장된 이벤트 전달의 지연이며 PG 조회를 추가 완료 조건으로 두는 B안과 다르다.
- Payment V19는 기존 조회 완료만 PG_QUERY_UNATTRIBUTED로 식별하고 새 성공 근거를 추정하지 않는다. 직접 성공의 선택적 PG 시각은 NULL을 허용한다. Order V17은 성공 이벤트·원금융 연결·취소 ID·지문을 추가하고 기존 지문을 바꾸지 않는다. 양쪽 모두 원 주문/결제 PAID·금액·비용·최초 결과를 보존한다.

자동 작업자/환불 API 연결·실제 PG 송신·확정 실패/복구/새 실행 정책은 이 단계에서 완료하지 않는다. 새 검증은 격리된 HTTP 계약 픽스처·DB·Kafka를 사용하며 실제 제공사 환불과 구분한다. Red는 Payment 도메인 4·정상 응답 어댑터 1·Order 도메인 4건을 실제 확인했다. 원자료는 runs/refund-success-tdd-9nvmjlg2다.

OCI full 실행에서 공통 Kafka 단위 191·Payment 단위 195·Order 단위 254건(단위 합계 640), Payment 통합 81·Order 통합 79건(통합 합계 160)이 모두 실패·오류·건너뜀 0으로 통과했다. 신규 통합 8건은 성공 뒤 GET 장애와 무관한 확정, 실제 HTTP 응답을 지연한 조회 선행 경합, 성공/근거/Outbox 롤백과 동일 근거 재개, 동일 응답 동시 저장, Order 직접 성공/Inbox 롤백, 실제 Kafka의 정상 직접 완료 및 늦은 성공/금융 이벤트 양방향 순서·중복을 검증했다. 공급자는 HTTP 계약 픽스처이며 실제 PG 자금 이동 검증으로 확대하지 않는다. 소스 1005개를 SHA-256 대조했고 공유 컨테이너 22개·볼륨 57개의 ID/시작/재시작/건강 상태를 보존했다. 추가·잔여 Testcontainers/볼륨이 없다. 원자료 명령/소스/로그/JUnit·보존 기록은 runs/refund-success-oci-6tndqndq/collected/results/full 및 preservation.json이다. 검증 이후에는 문서만 갱신했으며 로컬 단위·통합 컴파일·두 bootJar도 통과했다.

검증된 해시 이름 runtime JAR로 로컬 Order·Payment를 재개해 건강검사 UP·Order V17/Payment V19 적용을 확인했다. Auth·Catalog·SSH는 보존했다. 반영 전후 본인 주문/결제 API 응답 전체가 동일하며 기존 9,000원 주문·결제는 PAID다. 실제 PG 취소 POST는 보내지 않았다. 근거는 runs/refund-success-tdd-9nvmjlg2/live-before-activation.json·live-activation.json·activation.log다.


## 2026-10-27 REQUESTED 후 결과 조회 간격 B안

사용자는 결과 조회를 5초·15초·35초에 하고 이후 1분 간격으로 늦추는 B안을 선택했다. `REQUESTED`는 접수이며 완료가 아니다. 완료 근거가 없으면 보유권 정지와 같은 시도를 유지한다. 시간 경과나 조회 장애를 확정 실패·복구·새 시도로 해석하지 않는다. 이는 GET 조회 주기이며 취소 POST 재시도 주기를 결정한 것이 아니다.

시각의 기준은 이미 선택한 DB의 최초 취소 송신 준비 시각이다. 실제 PG 접수 시각을 추정하지 않는다. 최초 준비 트랜잭션에서 첫 조회 기한을 함께 저장해 송신 후 프로세스가 죽어도 복구한다. 정상 직접 성공 응답은 앞선 A안대로 추가 조회 없이 완료하고 일정을 해제한다. 기존 송신 서비스의 미확정 결과 직후 1회 조회도 유지하며, 이번 주기는 그 뒤의 백그라운드 보완이다.

| 최초 준비 후 경과 | 동작 |
|---|---|
| 5초 | 첫 정기 조회 |
| 15초 | 두 번째 조회 |
| 35초 | 세 번째 조회 |
| 95초·155초·… | 1분 간격 조회 |
| 3시간 이후 | GET 조회는 계속 허용한다. 기존 자동 취소 POST 제한은 유지한다. |

이는 빠른 응답을 전제로 한 목표 시각이다. 1초 스캔·제한된 실행 용량·HTTP 지연에 따라 실제 조회는 늦어질 수 있다. 서버가 오래 멈췄거나 HTTP가 오래 걸리면 지난 단계를 건너뛰고 현재 결과를 한 번 확인한 뒤 1분 간격을 사용한다. 밀린 조회를 몰아서 실행하거나 재시작 시 5초 단계로 초기화하지 않는다.

취소 관련 서명 검증 웹훅 `Transaction.CancelPending`·`Transaction.PartialCancelled`·`Transaction.Cancelled`는 첫 내구 접수와 함께 준비된 미확정 실행의 조회를 다음 스캔으로 앞당긴다. 웹훅 본문만으로 성공/실패를 확정하지 않는다. 같은 웹훅 ID·본문의 재전달은 재기동하지 않는다. 준비되지 않은 접수나 완료된 금융 사실은 깨우지 않는다. 웹훅 조기 조회는 정기 기한을 초기화하지 않으며, HTTP 중 도착한 알림도 완료 처리에 덮어써서 잃지 않는다.

`RefundLookupSchedule`·Payment V20은 다음 기한·단계·웹훅 깨우기·작업 임대 키/만료를 보존한다. `ResolveRefundLookup`은 Payment→실행 순서의 짧은 잠금으로 작업을 선점·커밋한 다음 잠금 밖에서 기존 `ReconcileRefundPayment` GET을 호출한다. 작업자끼리 같은 실행을 선점하지 못하며 프로세스 중단 시 임대 만료 후 재개한다. 이전 작업자의 늦은 종료는 새 임대를 덮어쓰지 못한다. 수용 용량 부족은 임대를 반납하고 단계도 소비하지 않는다. 기존 수동/송신 직후 조회와의 중첩까지 금지하는 것은 아니며 금융 사실/Outbox의 멱등 저장 경계는 그대로 유지한다.

실행은 최대 4개·대기 큐 0개로 제한한다. 임대 길이는 연결 제한+응답 제한+30초이며 현재 설정에서는 97초다. 환불 스캔과 기존 결제 처리는 별도 스케줄러를 사용한다. 금융 완료 확인 시 기한·깨우기·임대를 원자적으로 해제한다. 기본 설정은 `PAYMENT_REFUND_LOOKUP_ENABLED=false`이며 선택적으로 활성화한다. 자동 취소 POST 실행기·고객 환불 API·확정 실패/보유권 복구는 이 변경으로 연결하지 않는다.

선택의 비용은 정기 5초 조회에 비해 긴 대기의 발견이 최대 약 1분 늦어지는 점, 일정·임대 저장과 동시 처리 구현이다. 웹훅이 정상 도착하면 조기 조회로 보완한다. 조회 공급자 장애 중에도 같은 간격을 사용하며, 일시 장애를 곧바로 실패로 만드는 정책은 채택하지 않았다.


검증: 주기 모델 9개 사례에서 미구현 Red 8건을 실제 확인했다. 로컬 단위 206건·통합 소스 컴파일·bootJar를 통과했다. 원자료는 `runs/refund-lookup-tdd-1yp2c1o0/`이다. 최초 OCI full 실행은 테스트 정리에서 존재하지 않는 trace 테이블명을 사용해 통합 24건이 본문에 도달하지 못했다. 정리 코드를 고치고 `--rerun-tasks`로 전체를 재실행한 corrected에서 **Payment 단위 206·통합 93건**이 실패·오류·건너뜀 0으로 통과했다. 첫 실패를 TDD의 미구현 Red나 성공 검증으로 합치지 않는다.

신규 통합 12건은 실제 DB의 준비 전 제외·5/15/35/60 간격·최종 금융 확인 후 정기 작업 해제·장애 대기·3시간 이후 GET·두 스캐너의 선점 경합·HTTP 중 웹훅·동일 알림 재전달·실행 용량 거절·임대 만료·실제 flush 후 접수/깨우기 롤백·다른 공급자 제외·DB 제약·V19→V20 최초 시각 보존과 서명 웹훅 HTTP 경계를 검증한다. 기존 직접 성공 통합에도 조회 일정 해제 검사를 추가했다. 공급자 HTTP는 격리 계약 픽스처이며 실제 PG 환불 송신·License/Order/Settlement 전체 환불 E2E를 검증한 것이 아니다.

OCI 경로는 `/tmp/stove-refund-lookup-check-O4ndTMWD/work`, 원자료는 `runs/refund-lookup-oci-lg92j8wa/collected/results/{full,corrected}`다. 소스 1,014개를 SHA-256으로 대조했고 실제 API/웹훅 비밀값의 소스 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개의 ID/시작/재시작/건강 상태를 보존했고 추가·잔여 Testcontainers/볼륨이 없다. 이 단계는 Payment를 재검증했으며 앞선 Order·공통 Kafka 결과를 다시 실행한 것으로 합산하지 않는다. 명령은 다음과 같다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --rerun-tasks --continue -Pstove.infraTest.maxParallel=1 --console=plain --no-daemon --max-workers=2
```


검증된 해시 이름 runtime JAR로 로컬 실제 주문 환경의 Payment만 재개하고 `PAYMENT_REFUND_LOOKUP_ENABLED=true`를 적용했다. Payment V20·건강검사 UP·서로 다른 결제/환불 스캔 스레드를 확인했다. Auth/Catalog/Order 및 SSH PID는 보존했다. 적용 전후 본인 주문/결제 API 응답 전체가 같고 기존 9,000원은 PAID다. 실제 PG 취소 POST는 보내지 않았다. 근거는 `runs/refund-lookup-tdd-1yp2c1o0/live-before-activation.json`·`live-activation.json`·`activation.log`다.


## 2026-10-27 실패 후 미취소 조회와 권한 복구 B안

사용자는 검증된 PG `FAILED` 응답만으로 권한을 복구하는 A안 대신, **우리 취소 실패와 원결제 미취소를 함께 확인한 뒤 복구하는 B안**을 선택했다. 조회 장애 동안 고객의 이용 재개가 늦어지고 추가 조회/대조 코드가 필요한 비용을 받아들인다. 송신 직후의 실패 응답은 아직 복구 권한이 아니다. HTTP 오류·응답 유실·모르는 상태·시간 경과는 기존 미확정 경로를 유지한다. 포트원 [취소 가이드](https://developers.portone.io/opi/ko/integration/cancel/v2/readme?v=v2)와 [공식 V2 스키마](https://developers.portone.io/api/schema/v2/openapi.json)의 REQUESTED/FAILED/SUCCEEDED 취소 내역을 사용한다.

`REQUESTED` 또는 `FAILED` 정상 응답을 원래 호출 경로·attemptId·동결 본문에 연결하고 취소 ID·요청 시각·원액·세금·사유를 검증해 저장한다. 이 연결을 추후 조회에서 사유 문자열만으로 추정하지 않는다. 같은 취소 ID/필수 값은 불변이며 REQUESTED→FAILED 관측만 보완한다. 알려진 접수 이후에는 같은 시도의 자동 POST를 다시 보내지 않고 조회한다. PG 취소 ID 연결을 잃었으면 원금이 남아 있더라도 자동 실패/복구를 확정하지 않는 비용이 남는다.

추가 GET 한 번의 결과에서 다음을 함께 검증한다. 기존 정기 GET도 연결된 접수가 있으면 같은 검사를 수행한다.

- 원결제·상점·채널·TEST/TOSSPAY_V2·V2·원 PG 거래·주문·통화·결제 시각·원액이 기존 구매와 같다.
- 결제는 PAID이고 total=paid=원액, discount=cancelled=0이다.
- 연결된 우리 취소 ID의 상태가 FAILED이며 원액·요청 시각·세금·사유도 저장한 접수와 같다.
- 취소 이력에 진행 중·성공·알 수 없는 취소가 없고 취소 ID가 중복되지 않는다.

취소 ID 누락·다른 취소의 실패·부분 취소·REQUESTED·조회 장애·불완전/모순 자료는 복구를 만들지 않는다. 먼저 전액 취소가 검증되면 기존 A안에 따라 금융 완료를 기록하고 복구하지 않는다. FAILED 내역의 reason은 취소 요청 사유이므로 PG의 실패 원인으로 표시하지 않는다.

Payment는 짧은 자체 트랜잭션에서 원결제→실행을 잠그고 원 요청/취소 연결을 재확인한다. FAILED·최초 실패 시각/이벤트·조회 본문 지문과 `RefundExecutionFailed` Outbox를 함께 저장하고 정기 조회를 해제한다. 중복은 첫 근거를 보존하며 금융 완료가 먼저 커밋됐다면 뒤늦은 실패로 복구하지 않는다. HTTP 중 DB 잠금은 유지하지 않는다. V21은 nullable 접수/실패 근거를 추가하며 기존 PENDING을 실패로 추정하지 않는다.

Order는 현재 주문·환불·시도·실행 요청을 대조해 시도 FAILED·Inbox·복구 요청 Outbox·`PENDING_LICENSE_RESTORATION`을 원자 저장한다. 복구 요청만으로 복구 완료를 기록하지 않는다. `LicenseRestorationRequested`는 원래 licenseId·실제 정지 요청/완료·실패 이벤트·attemptId를 포함한다. Payment 실패와 License 복구 완료는 각 발행 모듈의 토픽에서만 수신한다.

License는 구매/보유권·현재 정지·불변 정지 이력을 대조하고 실제 ACTIVE 전환·복구 이력·Inbox·`LicenseRestored` Outbox를 함께 저장한다. 완료된 복구의 재전달은 같은 근거에서만 멱등 처리하며 이후 다른 정지를 풀지 않는다. 예전 정지 메시지의 재전달도 복구를 되돌리지 않는다. Order는 실제 완료의 모든 상관키를 대조한 뒤 환불 상태를 FAILED로 마친다. 원 PAID 구매·최초 환불 접수·당시 자동 자격·동일 실행 이력은 보존한다. 자동 새 시도는 만들지 않는다. Order V18·License V8이 이력을 추가한다.

조회와 외부 관리자 취소를 하나의 트랜잭션으로 묶을 수는 없다. 조회 직후 다른 경로에서 새 취소가 발생하는 경합은 B안도 완전히 차단하지 못한다. 확정 근거와 모순되는 지연 결과를 자동으로 덮어쓰거나 이미 복구한 권한을 임의로 다시 정지하지 않으며 운영 대조가 필요한 범위로 남긴다. 새 시도 허용/API·자동 취소 POST 시작/재시도 주기·장기 대기 운영 안내·실제 PG 환불·전체 모듈 E2E는 이번 선택으로 확정하거나 완료하지 않는다.


TDD는 Payment 3·Order 2·License 2건의 미구현 Red 7건을 확인한 뒤 구현했다. 최초 회귀에서 새 이벤트 카탈로그 누락과 변경 전 REQUESTED/FAILED 기대값을 발견해 보완했고, KafkaTemplate의 기존 고정 제네릭 계약과 맞지 않은 통합 테스트 송신 코드는 기존 송신 도우미로 수정했다. 원자료는 `runs/refund-restoration-tdd-ur27gau4/`다. 최종 로컬 단위는 공통 Kafka 203·Payment 213·Order 263·License 99건이 모두 통과했고, 세 모듈 통합 컴파일·bootJar도 통과했다.

OCI 최종 결과는 실행별로 구분한다. 모든 수치는 실패·오류·건너뜀 0이다.

| 모듈 | 단위 | 통합 | 출처 |
|---|---:|---:|---|
| 공통 Kafka | 203 | — | full |
| Payment | 213 | 98 | 단위 full, 통합 payment-final |
| Order | 263 | 83 | updated |
| License | 99 | 53 | full |

full은 7개 태스크 전체 실행을 통과했다(Payment 통합 97, Order 단위 261/통합 83, 나머지는 위와 동일). 이후 Order 수신 토픽 경계를 보강하고 updated에서 Order 단위/통합 전체를 --rerun-tasks로 재실행했다. 운영 코드가 같은 Payment에는 관리자 전액 취소 경합 픽스처 1건을 추가하고 payment-final에서 통합 전체 98건을 --rerun-tasks로 재실행했다. 과거 XML을 새 태스크 실행 결과로 합산하거나 세 실행을 단일 전체 실행으로 표시하지 않는다.

신규 통합 14건은 Payment 5·Order 4·License 5건이다. 실패 응답만으로 복구하지 않음·추가 조회 장애/진행 취소/부분 취소의 보류·REQUESTED 접수의 재시작 후 실패 확인·다른 경로의 전액 취소 시 금융 완료·실제 flush 후 실패/요청/권한/이력/Inbox/Outbox 롤백과 재개·금융 완료 선행 시 실패 거절·실제 복구 완료 대기·동시 복구·이전 정지/복구 재전달로 새 정지를 변경하지 않음·실제 Kafka 중복과 단계별 반영을 검증했다. 단위에는 세 경계의 필수 근거·봉투/본문/키와 모듈 토픽 검증을 포함했다. PG HTTP는 격리 계약 픽스처다.

소스 1,043개를 SHA-256 대조했다. 최초 full 이후 운영 코드 변경은 Order 토픽 경계 한 곳뿐이며 해당 모듈을 재검증했다. 최신 검증 이후 소스 변경은 문서뿐이다. 공유 컨테이너 22개·볼륨 57개의 ID/시작/재시작/건강 상태를 보존했고 추가·잔여 Testcontainers/볼륨은 없다. 비밀값의 소스 포함은 0건이다. 원격은 `/tmp/stove-refund-restoration-check-EpKxdIk5/work`, 원자료는 `runs/refund-restoration-oci-fg84qzci/collected/results/{full,updated,payment-final}`의 소스/명령/로그/JUnit 및 보존 기록이다.


검증된 해시 이름 runtime JAR로 실제 주문 검증 환경의 Order·Payment만 재개했다. 건강검사는 UP이고 Order V18·Payment V21 적용을 확인했다. Auth·Catalog·SSH PID와 읽기 전용 환불 조회 활성화를 보존했다. 적용 전후 본인 주문/결제 API 응답 전체가 동일하고 기존 9,000원은 PAID다. 실제 PG 취소 POST는 보내지 않았다. 이 환경에서 License 서비스를 새로 실행한 것은 아니며 License 복구는 OCI 격리 통합으로 검증했다. 근거는 `runs/refund-restoration-tdd-ur27gau4/live-before-activation.json`·`live-activation.json`·`activation.log`다.


## 2026-10-27 복구 뒤 명시적 재시도 A안

사용자는 확인된 환불 실패와 실제 권한 복구까지 마친 고객이 다시 요청하면, 추가 운영 승인 없이 새 실행을 허용하는 A안을 선택했다. 최초 환불 자격은 유지하고 사용자 의사 없이 자동 새 시도를 만들지 않는다. 같은 원인이 남아 있으면 다시 실패하고 새 처리 동안 권한이 다시 정지되는 비용은 남는다. 이 변경은 내부 유스케이스/이벤트 계약이며 고객 HTTP API나 자동 취소 POST 실행기를 제공하는 것은 아니다.

Order `RequestRefundRetry`는 구매자·주문과 고객이 지정한 이전 실패 attemptId를 대조한다. 그 시도의 검증된 실패 이벤트와 실제 복구 완료가 있어야 하며, 첫 접수/자격/refundId/실패·복구 이력은 보존한다. 재시도 접수 시에는 현재 실패 시도를 유지하고 새로운 LicenseSuspensionRequested Outbox와 PENDING_LICENSE_SUSPENSION을 함께 저장한다. 실제 새 정지 완료가 확인된 뒤에만 순번+1·새 attemptId·새 실행 요청을 저장하고 현재 실행을 바꾼다.

같은 이전 실패 번호를 지정한 재전달은 같은 RefundRetryReceipt(첫 재접수 시각·새 정지 요청 ID)를 반환한다. 다음 시도가 실패·복구된 뒤 늦게 도착해도 또 다른 시도를 만들지 않는다. 그 다음 재시도를 원하면 새 실패 번호를 지정해야 한다. Order 행 잠금과 재시도 필드의 일회 기록, 이전 시도당 단일 후속 실행 DB 유일 제약으로 동시 요청도 하나로 처리한다. Outbox 저장 실패 시 접수/상태/필드도 함께 롤백한다.

새 실행 요청에는 optional previousAttemptId를 추가한다. 최초 요청은 이전 필드가 없는 JSON/기존 생성자도 계속 지원하며, 후속 요청은 이전 실행을 명시해야 한다. Payment는 같은 PAID 구매·refundId·검증된 FAILED 이전 실행·새 실제 정지 완료를 확인하고 한 선행 실행에 한 후행 실행만 접수한다. 결과 미확정·금융 완료·알 수 없는 이전 실행·분기 요청·재사용한 정지 완료는 거절한다. 같은 실행의 재전달은 이전 접수와 추적 부모를 보존한다.

Payment의 새 실행은 자신만의 멱등 키(attemptId)·최초 본문·최초 준비 시각·3시간 창을 갖는다. 이전 키/본문/창/실패 근거를 초기화하지 않는다. 송신 전에는 같은 결제의 원액=취소액=예상 잔액을 유지하고, 이전 시도 연결을 따라 저장된 실패 취소 이력 전체와 PG의 FAILED 취소 ID/금액/시각/세금/사유를 대조한다. 우리에게 확인된 실패 이력만 예외로 허용한다. 누락·변경·추가 취소·진행 중 취소·부분 취소는 자동 POST를 허용하지 않는다. PG가 과거 실패 이력을 반환하지 않는 등 대조가 불가능한 상황의 운영 확인 비용이 남는다. 최초 실행의 ‘취소 이력 없음’ 조건은 유지한다. [공식 멱등 키/3시간 계약](https://developers.portone.io/api/rest-v2)과 [취소 상태](https://developers.portone.io/opi/ko/integration/cancel/v2/readme?v=v2)를 따른다.

정상 웹훅은 첫 과거 실행 대신 현재 미확정 실행을 깨운다. 이미 처리한 정지/실패/복구 메시지는 불변 근거와 Inbox를 대조해 멱등 처리하며, 오래된 결과가 새 정지나 현재 실행을 덮어쓰지 않는다. 다른 내용·알 수 없는 연결·모순된 금융 결과는 새 시도 성공이나 실패로 추정하지 않는다. License의 기존 복구 재전달도 후속 정지를 풀지 않는 계약을 유지한다.

Order V19·Payment V22는 nullable 이전 실행 연결/재시도 접수 필드·선행당 단일 후행 유일 제약·자기 참조 FK를 추가한다. Payment는 실제 정지 완료의 실행별 유일성도 강제한다. 기존 접수를 새 재시도로 추정해 채우지 않는다. 최신 A안은 추가 운영 승인 여부를 정한 것이며, HTTP 오류의 나머지 분류·관리자 업무·전체 실제 PG 환불 E2E·새 흐름의 Tempo 전체 연결은 후속이다.


검증: Order의 미구현 Red 3건과 Payment의 corrected 기준 미구현 Red 1건을 확인했다. Payment 초기 Red는 모의 저장소의 인자 설정 충돌이어서 업무 Red로 세지 않는다. 설정을 수정한 뒤 변경 전 ReceiveRefundExecution으로 ‘기존 실행이 있으면 확인된 FAILED도 거절’하는 실패를 실제 재현하고 현 구현을 복원했다. 기존 Order 중복 정지 단위 픽스처도 현재 ID 대신 정지 완료 이력 조회에 맞췄다. 원자료는 `runs/refund-retry-tdd-5qwhady7/`의 order-red.log·payment-red.log·payment-red-corrected.log/xml·green/빌드 로그다.

로컬 단위와 OCI full 모두 공통 Kafka 205·Payment 218·Order 268건을 통과했다. OCI 통합은 Payment 102·Order 87건이다. 다섯 태스크는 같은 full 입력에서 실패·오류·건너뜀 0으로 통과했다. 단위 합계 691·통합 합계 189건이다. 새 통합은 Order 4·Payment 4건이며 기존 Order Kafka 사례를 실패/복구→명시적 재접수→새 실제 정지→두 번째 실행→옛 메시지 재전달까지 확장했다. DB의 중복/동시 접수·실제 flush 후 접수/Outbox 롤백·원 자격/이력 유지·권한 복구 전 및 다른 구매자 거절·단일 선행당 후행 실행·연속 두 번 실패 뒤 세 번째 시도의 새 PG 키/창과 한 번의 환불·다른 진행 취소 시 미송신·현재 실행의 웹훅 깨우기를 검증했다. 두 모듈 통합 컴파일·bootJar도 통과했다.

OCI 원격은 `/tmp/stove-refund-retry-check-Llu7TULz/work`, 원자료는 `runs/refund-retry-oci-q_c7wgvb/collected/results/full`의 소스/명령/로그/JUnit과 전후 보존 기록이다. 소스 1,050개를 SHA-256 대조했고 비밀값 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개와 ID/시작/재시작/건강 상태를 보존했으며 추가·잔여 Testcontainers/볼륨이 없다. 운영/테스트 코드는 검증 해시와 같고 이후 변경은 문서뿐이다. License 코드는 바꾸거나 이번 실행에서 다시 검증하지 않았으며 기존 재정지/복구 계약을 사용한다. HTTP 공급자·License 완료 메시지는 격리 계약 픽스처이며 전체 실제 환불 E2E의 완료 기록이 아니다.

```sh
./gradlew :common:kafka:test :apps:payment:test :apps:order:test :apps:payment:integrationTest :apps:order:integrationTest --continue -Pstove.infraTest.maxParallel=1 --console=plain --no-daemon --max-workers=2
```

검증된 해시 이름 runtime JAR로 로컬 실제 주문 환경의 Order·Payment를 재개했다. 건강검사는 UP이고 Order V19·Payment V22를 확인했다. Auth·Catalog·SSH PID와 읽기 전용 환불 조회 활성화를 보존했다. 적용 전후 본인 주문/결제 API 응답 전체가 같고 기존 9,000원은 PAID다. 실제 PG 취소 POST나 고객 환불 HTTP API 호출은 하지 않았다. 근거는 `runs/refund-retry-tdd-5qwhady7/live-before-activation.json`·`live-activation.json`·`activation.log`다.


## 2026-10-27 이벤트 소비자의 첫 취소 송신 A안

사용자는 기존 Order Outbox → 릴레이 → Kafka 전달을 유지하고, Payment 이벤트 소비자가 접수 커밋 뒤 PG 첫 취소 호출까지 수행하는 A안을 선택했다. 별도 송신 작업자가 Payment DB를 주기적으로 확인하는 B안은 처리량에 따른 고도화 계획으로 남긴다. 현재 단계에서는 추가 실행 구조를 줄이는 대신 느린 PG가 같은 소비자의 후속 환불 처리를 지연시키는 비용을 수용한다. Order Outbox 발행 완료는 Kafka 전달이며 PG 환불 완료가 아니다.

RefundExecutionEventListener는 봉투/본문/키를 검증하고 ReceiveRefundExecution을 호출한다. 접수·Inbox·추적 부모 트랜잭션이 커밋된 뒤 RequestFirstRefundCancellation으로 이어간다. 기존 접수의 정상 중복도 실행 상태를 확인하므로 접수 커밋 후 첫 송신 준비 전에 중단된 건은 이벤트 재전달로 시작할 수 있다. 접수 실패는 PG를 호출하지 않고 Kafka 처리 실패로 남긴다. HTTP 중 DB 트랜잭션/잠금을 유지하지 않는다. 전용 Kafka factory는 한 번에 가져올 레코드를 1개로 제한해 여러 긴 PG 호출을 한 poll 배치 안에서 누적하지 않는다. PG 지연의 같은 소비자/파티션 대기는 여전히 존재한다.

첫 송신 권한은 기존 Payment → RefundExecution 잠금 안에서 최초 준비 시각·고정 본문·GET 일정과 함께 확정한다. executeFirst는 준비가 없던 실행 한 호출만 외부 송신으로 진행한다. 동시 호출·재전달에서 이미 준비된 실행은 GET만 수행한다. 정상 성공/확정 실패는 종료 상태를 보존하고, REQUESTED/불완전 응답/PG 통신 장애는 실패나 권한 복구로 바꾸지 않는다. PgUnavailable은 이미 저장한 GET 일정으로 이어지므로 일반 Kafka 재시도를 POST 재시도 간격으로 사용하지 않는다. DB 저장 실패 등은 소비 실패로 전파하며 재전달은 저장 상태를 다시 확인한다. 기존 수동 호출 서비스의 같은 키/본문·3시간 송신 제한은 유지한다.

이 단위는 **첫 송신 시작 연결**이다. 준비 커밋 직후 실제 HTTP 전에 죽거나 사전 조회가 실패하면, 저장 상태만으로 ‘PG에 보내지 않았음’을 확정할 수 없으므로 재전달 시 GET 대기에 남을 수 있다. 타임아웃만으로 새 시도/실패/복구를 만들지 않는다. 이 대기를 해소하는 같은 시도의 POST 재송신 조건·간격과 나머지 HTTP 오류 분류는 다음 정책 결정이며 이번 구현으로 자동 승인하지 않는다. 직접 성공 응답의 DB 저장이 실패한 뒤에는 GET으로 금융 완료를 복구할 수 있지만, 잃어버린 응답의 시도 귀속을 추정하지 않는다.

활성화는 `PAYMENT_REFUND_EXECUTION_ENABLED=true`이며 기본값은 false다. 기존 읽기 전용 `PAYMENT_REFUND_LOOKUP_ENABLED=true`만으로 POST를 켜지 않는다. 송신 활성화에는 PortOne 취소 서비스와 durable GET 작업자 모두 필요하며, 조회 기반 없이 송신만 켜면 시작을 실패시킨다. 이미 소비가 완료된 과거 접수는 새 스캔이 자동으로 찾아주지 않으므로 재개 시 이벤트 재전달 등 별도 처리가 필요하다. 고객 환불 HTTP API·실제 PG 환불 E2E는 후속이다.

### B안: 처리량 증가 시 별도 송신 작업자로 분리

B안의 목적은 PG 지연과 이벤트 접수를 분리하고 PG 동시 호출을 독립적으로 제한하는 것이다. 기존 Outbox/Kafka를 교체하거나 같은 이벤트를 다시 전달하기 위한 장치를 추가하는 것이 아니다.

- 전환 판단: 부하 검증/운영 관측에서 PG 대기와 함께 환불 소비 지연·Kafka 적체가 반복되거나, 필요한 동시 처리량이 현재 소비자의 처리 능력을 넘으면 B안을 검토한다. 초기에는 근거 없는 고정 초당 건수로 전환을 결정하지 않는다.
- 후속 측정: 실행 요청 발생→Payment 접수 지연, 접수→최초 송신 준비 지연, PG 응답 시간(p95/p99), 환불 소비 그룹의 lag·재시도/실패 토픽 유입, 가장 오래 기다리는 환불을 관찰한다. 새 계측 전체를 이번에 구현한 것으로 표시하지 않는다. 서비스 목표와 부하 결과에 따라 허용 대기 기준을 정한다.
- 구현 계획: 소비자는 접수만 커밋하고, 별도 작업자가 DB의 송신 대상을 찾는다. 내구성 있는 작업 상태/선점 임대·만료 재개·동시 호출 상한을 추가한다. 초기 1초 확인 간격은 검토 시작값이며 확정 운영 SLA가 아니다. PG 호출 중 잠금을 잡지 않고 기존 취소 서비스·본문·attemptId·3시간 기한·GET 일정을 재사용한다.
- 전환/검증 비용: A와 B가 동시에 같은 실행을 송신하지 않도록 소유권을 전환하고, 기존 접수 누락 없이 재개해야 한다. 작업자 중단·임대 만료·동시 선점·중복 메시지·PG 지연·부하/배압을 검증한다. 작업자가 모두 바쁘면 실행 대기는 남으며, 미확정 POST 재송신 정책은 B로 분리해도 별도로 필요하다.


검증 기록: 최초 TDD에서 이벤트 수신 후 첫 호출 누락·중복 POST·준비 이후 재송신·PG 불확실성의 Kafka 전파 4건을 Red로 확인했다. OCI에서 발견한 실제 리스너의 `max.poll.records` 누락은 별도 회귀 테스트로 재현한 뒤, factory 원형 대신 생성 완료한 컨테이너를 조정하도록 고쳐 Red 1건을 추가했다. 첫 설정 회귀 테스트의 존재하지 않는 endpoint 클래스에 따른 컴파일 오류는 업무 Red에 포함하지 않는다. 로컬 최종 Payment 단위 229건·통합 소스 컴파일·bootJar가 통과했다. 원자료는 `runs/refund-event-tdd-rb272_9q/`의 red-junit/all-red-results.json·kafka-config-red.xml·final-build.log다.

OCI 실행은 full → corrected → final로 보존한다. full의 Payment 단위 228건은 통과했고 통합 109건 중 2건이 테스트 기대값/모의 객체 설정 오류로 실패했다. 직접 성공은 금융 완료와 시도 성공을 하나의 확인 이벤트로 발행하므로 별도 성공 이벤트 개수를 0으로 수정했으며, MANDATORY 트랜잭션 메서드의 spy 설정을 트랜잭션 안에서 수행했다. corrected에서는 단위 228건 통과·통합 109건 중 실제 Kafka 설정 누락 1건이 실패했다. 최종 운영 설정 수정/회귀 추가 후 **final의 Payment 단위 229·통합 109건이 모두 실패·오류·건너뜀 0**으로 통과했다. 과거 실행의 XML을 최종 수치에 합치지 않는다.

새 통합 7건은 실제 Kafka/DB/HTTP로 접수·준비 커밋의 외부 관측, 동일 이벤트 재전달과 단일 송신/금융 결과, 접수 후 중단의 재개, 응답 불확실 시 Kafka 처리 완료·POST 미반복·기존 GET 작업자 회복, 준비 후 미송신 가능 구간의 GET 대기, PG 성공 뒤 DB flush/Outbox 롤백과 GET 복구, 느린 PG 중 현재 offset 미완료·같은 파티션의 다음 접수 대기, 동시 첫 호출의 단일 송신을 검증했다. 검증 PG는 격리 HTTP 계약 픽스처이며 실제 포트원 취소나 전체 환불 E2E의 완료가 아니다.

소스 1,057개를 SHA-256 대조했다. 실제 비밀값 포함 0건이며 공유 컨테이너 22개·볼륨 57개와 ID/시작/재시작/건강 상태를 보존했다. 추가·잔여 Testcontainers/볼륨은 없다. OCI 격리 경로는 `/tmp/stove-refund-event-check-iab8UWvg/work`, 원자료는 `runs/refund-event-oci-edsrov3x/collected/results/{full,corrected,final}`과 preservation.json이다. 검증 이후 운영/테스트 소스는 같으며 문서만 갱신했다. 이번 실행은 Payment만 재검증했고 Order·License·공통 Kafka의 이전 결과를 재실행 수치로 표시하지 않는다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --continue -Pstove.infraTest.maxParallel=1 --rerun-tasks --console=plain --no-daemon --max-workers=2
```


검증된 해시 이름 runtime JAR로 로컬 실제 주문 환경의 Payment만 갱신하고 첫 송신/기존 결과 조회를 활성화했다. Payment 건강 상태는 UP, 스키마는 기존 V22다. Auth·Catalog·Order·SSH PID를 보존했고 적용 전후 본인 주문/결제 API 응답 전체가 동일하다. 기존 9,000원은 PAID이며 환불 실행은 0건으로 실제 PG 취소 POST는 보내지 않았다. 공개 HTTPS 웹훅 경로는 인증서 검증 성공·서명 없는 POST 401을 확인했다. 근거는 runs/refund-event-tdd-rb272_9q/live-activation.json·activation.log·public-webhook-smoke.json이다. 전체 실제 PG 환불 E2E는 후속이다.


## 2026-10-27 명확한 조건의 동일 시도 자동 재송신 A안

사용자는 응답을 받지 못한 취소 요청도 조건이 명확하면 같은 시도로 자동 재송신하는 A안을 선택했다. 준비를 저장한 뒤 HTTP 전에 중단돼 PG에 도착하지 않은 요청을 자동으로 진행할 수 있도록 한다. 이번 선택은 재송신 허용 조건이며 간격은 아직 선택하지 않았다. 기존 이벤트 소비자의 첫 송신 A안과 처리량 증가 시 별도 송신 작업자 B안의 고도화 계획은 유지한다.

허용 조건은 다음과 같다. Payment의 구매자·주문·금액을 원래 접수와 대조하고, 정상 PG 조회로 원결제·주문 연결·금액·통화·PG 거래를 대조해야 한다. 원결제 전액과 취소 요청액·현재 예상 취소 가능 잔액이 같아야 한다. 최초 실행은 취소 이력이 없어야 하고, 명시적 후속 실행은 저장된 이전 FAILED 이력 전체와 정확히 일치해야 한다. 우리 시도의 REQUESTED/FAILED 정상 접수 근거, 진행/추가/변경/누락된 취소 이력, 부분 취소·잔액 변경 중단 근거 또는 금융 완료·확정 실패가 있으면 자동 재송신하지 않는다. 조회 자체가 실패하거나 검증이 불가능해도 POST하지 않는다.

같은 attemptId/멱등 키·최초 고정 본문·최초 송신 준비 시각을 유지한다. 자동 POST는 최초 준비 커밋부터 3시간 미만에서만 허용하고, 조회 뒤 실제 POST 직전에 다시 검사한다. 재송신으로 시도/키/본문/3시간 창을 새로 만들지 않는다. 3시간 이후에도 기존 GET으로 결과를 확인하며 시간 경과나 타임아웃으로 실패/권한 복구를 추정하지 않는다.

반례 검토: GET에 아직 PAID/취소 이력 없음으로 보여도 앞선 취소 요청이 PG 내부에서 처리 중일 수 있다. 따라서 조회를 ‘이전 요청 미도달’의 증거로 해석하지 않는다. 같은 키·같은 본문의 재전송을 중복 실행하지 않는 제공사 계약에 의존하고, IDEMPOTENCY_OUTSTANDING_REQUEST는 기다려야 하는 미확정으로 다룬다. 이미 REQUESTED 접수 근거가 있으면 기존 조회를 계속한다. 사전 조회와 POST 사이의 외부 수동 취소 경합은 기존 currentCancellableAmount와 같은 키로 방어하며, 잔액 불일치는 자동 POST 중단 근거로 보존한다. 근거가 모순되면 자동 보완/추가 취소를 하지 않는다. [포트원 공식 멱등 키·3시간·처리 중 응답 계약](https://developers.portone.io/api/rest-v2)을 다시 확인했다.

비용은 반복 PG 조회/POST, 내구성 있는 재송신 일정과 동시 실행/중단 후 재개 관리, 장기 대기 운영 확인이다. Kafka의 일반 재전달 간격을 곧바로 환불 POST 일정으로 사용하지 않는다. 다음은 ‘얼마나 기다린 뒤 다시 송신 대상으로 삼을지’ 한 쟁점이다. 간격 합의 뒤 허용 조건과 일정을 묶어 TDD·구현·OCI 격리 검증한다. 이 기록 시점에는 새로운 자동 재송신 실행기를 연결하거나 재송신 완료를 검증한 것으로 표시하지 않는다. 현재 executeFirst의 준비된 실행 GET 전용 동작은 유지 중이다.


## 2026-10-27 동일 시도 재송신 간격 B안

사용자는 명확한 조건의 자동 재송신 A안에 이어 간격 B안을 선택했다. 첫 송신·사전 조회·결과 확인 한 회가 끝나 미확정으로 남은 시각부터 10초를 기다리고, 다음 회가 끝나면 30초, 이후에는 매번 1분을 기다린다. PG 호출이 오래 걸렸다고 다음 송신을 겹치거나 밀린 횟수를 몰아서 실행하지 않는다. 기존 GET 주기(최초 준비 후 5·15·35초, 이후 1분)와 POST 재송신 일정은 별개다.

첫 송신은 기존 Kafka 소비자가 접수 커밋 뒤 수행하는 A안을 유지한다. RequestRefundCancellation은 첫 송신과 재송신 모두 Payment→실행 행 잠금의 짧은 트랜잭션에서 동일 실행의 작업 임대·고정 본문·원래 준비 시각을 확정한 뒤 잠금 밖에서 HTTP를 수행한다. 재송신은 별도 ResolveRefundRetry 스캔이 DB의 대상을 찾아 임대를 먼저 커밋하고, 큐가 없는 최대 1개 작업자에게 배정한다. 스캔 자체는 1초 주기이고 전용 스케줄러를 사용한다. 이것은 미확정 시도의 시간 재개이며, 처리량에 따른 ‘첫 송신을 소비자에서 별도 작업자로 이전하는 B안’은 고도화 계획으로 남는다.

RefundCancellationSchedule은 다음 시각·단계·임대 토큰/시작/만료·마지막 완료 관측 시각을 저장한다. 한 회가 끝나면 실제 완료 시각을 기준으로 다음 기한을 정한다. 제출 거절은 새 실행을 하지 않고 임대만 풀며 단계는 소비하지 않는다. 정상 호출 중에는 다른 소비자/스캐너가 같은 실행의 POST를 시작하지 않는다. 오래된 작업자의 종료가 새 임대와 일정을 덮어쓰지 못한다.

서버가 임대 저장 뒤 중단될 수 있으므로, 임대 취득 시 ‘임대 만료+그 회의 대기 간격’도 보수적인 재개 시각으로 저장한다. 완료가 관측되면 실제 완료+간격으로 대체한다. 임대 만료 후 대기까지 지난 작업은 새 소유자가 한 회만 재개한다. HTTP 최대 세 번(사전 GET·POST·결과 GET)의 설정상 연결/읽기 제한 합계+30초를 임대로 주입하며 현재 설정에서는 231초다. 송신 권한에도 임대 만료를 담아 사전 조회가 늦어졌을 때 POST 직전에 원래 3시간과 임대 기한을 함께 검사한다. 이미 PG에서 시작한 외부 처리를 임대가 취소할 수는 없으므로 중단/아주 느린 이전 처리의 재개는 같은 키·본문에 대한 제공사 멱등 보장에 의존한다.

재송신 때마다 기존 전액 사전 조회를 수행한다. 최초 실행은 취소 이력 없음, 명시적 후속 실행은 저장한 모든 선행 FAILED 이력과 정확한 일치가 필요하다. 부분 잔액 변경은 자동 POST 중단을 저장한다. REQUESTED/FAILED 접수 근거가 있거나 금융 완료/확정 실패·3시간 중단이 되면 POST 일정을 해제하고 기존 GET/실패 복구 계약을 따른다. 조회 장애·예상 밖 이력은 POST하지 않고 다음 간격으로 확인한다. 오류/대기만으로 새 attemptId·새 키/본문/준비 시각·새 3시간 창·확정 실패·권한 복구를 만들지 않는다.

V23은 내구 일정/임대 필드·단계/시간 제약·조회 인덱스를 추가한다. 과거 완료 시각은 추정해 채우지 않는다. 기존 준비와 고정 본문이 있고 자동 재송신 조건에 해당하는 행은 활성화한 스캐너가 첫 관측부터 보수적으로 10초를 기다리도록 기록한다. 고정 본문 자체가 없는 과거 준비는 같은 본문임을 입증할 수 없어 자동 재개 대상으로 삼지 않는다.

자동 재송신 활성화는 `PAYMENT_REFUND_RETRY_ENABLED=true`이며 기본 false다. 기존 읽기 전용 조회/첫 송신 플래그만으로 POST 재송신을 켜지 않는다. 재송신은 첫 송신과 GET 작업자 조립을 전제로 하며, 선행 조립 없이 재송신만 켜면 시작을 실패시킨다. 추가 PG 호출·단계/임대 저장과 테스트 비용, 임대 만료까지의 보수적인 중단 회복 지연을 수용한다. 검증 결과는 아래 실행별 기록을 따른다. 실제 PG 환불 E2E·고객 환불 HTTP API·나머지 HTTP 오류의 상세 업무 분류는 후속이다.


검증 기록: 일정 도메인 7건과 스캔/배정 4건의 미구현 Red 11건을 확인한 뒤 구현했다. 이후 송신 완료 기준의 대기·REQUESTED 중단·3시간 경계·늦게 시작한 소유자 거절·사전 조회 중 임대 만료·설정 경계를 회귀로 보강했다. 로컬 최종 Payment 단위 249건·통합 소스 컴파일·bootJar가 통과했다. 원자료는 `runs/refund-resend-tdd-ui0rl4k9/`의 schedule-red-junit/worker-red-junit·all-red-results.json·final-local-build.log다.

OCI full은 단위 249건 통과, 통합 121건 중 신규 복구 사례 2건이 실패했다. 기존 DIRECT_SUCCESS 픽스처는 이전 POST가 있으면 GET을 거절하는 모드였으므로 장애 회복을 표현하는 RETRY_SUCCESS 모드로 신규 사례만 수정했다. 운영 코드는 바꾸지 않았다. 이후 --rerun-tasks로 수행한 corrected 전체 실행에서 **Payment 단위 249·통합 121건이 실패·오류·건너뜀 0**으로 통과했다. 최초 실행과 수정 실행의 소스/로그/JUnit을 구분해 보존하며 과거 XML을 최종 수치에 합치지 않는다.

새 통합 12건은 실제 DB/Kafka/HTTP로 10·30·60초 단계의 내구 재개와 같은 키/본문/첫 준비 보존, 과거 준비 관측 후 대기, 부분 잔액 중단·예상 밖 진행 취소 보류·조회 실패의 미송신, REQUESTED 접수 뒤 POST 정지, 임대 만료+대기 이후 재개와 늦은 완료 거절, 작업자 포화의 단계 보존, 두 스캐너의 단일 송신, 3시간 경계와 GET 유지, Kafka 첫 미확정 뒤 실제 자동 재송신 타이머, 첫 Kafka HTTP와 재송신의 중복 실행 방지를 검증했다. 성공 때 금융 완료/Outbox를 한 번 저장하는 기존 계약도 유지한다. 공급자는 격리 HTTP 계약 픽스처이며 실제 PG 환불/전체 환불 E2E 검증으로 확대하지 않는다.

소스 1,066개를 SHA-256 대조했고 실제 비밀값 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개와 ID/시작/재시작/건강 상태를 보존했으며 추가·잔여 Testcontainers/볼륨은 없다. 원격은 `/tmp/stove-refund-resend-check-YPqmmJ24/work`, 원자료는 `runs/refund-resend-oci-hpe9gmgp/collected/results/{full,corrected}`와 preservation.json이다. 운영/테스트 코드는 최종 검증 해시와 같고 이후 변경은 문서뿐이다. 이번 실행은 Payment만 재검증하며 Order·License·공통 Kafka의 이전 수치를 재실행 결과로 표시하지 않는다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --continue -Pstove.infraTest.maxParallel=1 --rerun-tasks --console=plain --no-daemon --max-workers=2
```


검증된 runtime JAR로 로컬 실제 주문 환경의 Payment만 갱신해 V23 마이그레이션과 자동 재송신을 활성화했다. 건강 상태는 UP이며 첫 송신·결과 조회·재송신 플래그를 함께 유지한다. Auth·Catalog·Order·SSH PID는 보존했고, 적용 전후 본인 주문/결제 API 응답 전체가 같다. 기존 9,000원은 PAID·환불 실행 0건이며 실제 PG 취소 POST는 보내지 않았다. 공개 HTTPS 웹훅의 인증서 검증과 서명 없는 요청 401도 확인했다. 근거는 runs/refund-resend-tdd-ui0rl4k9/live-activation.json·activation.log·public-webhook-smoke.json이다. 이번 검증은 전체 실제 PG 환불 E2E의 완료가 아니다.


## 2026-10-27 인증 오류 401 뒤 자동 재개 B안

사용자는 401 인증 오류 뒤 운영자가 올바른 키로 설정을 고치면, 별도 환불별 재개 승인 없이 기존 조건 안에서 자동으로 이어가는 B안을 선택했다. 이 동작은 기존 일반 미확정 처리/재송신에 이미 포함돼 있었다. 이번 변경은 401을 명시적으로 식별하는 분류와 회귀 검증이며, 새 중단 상태·운영 해제 절차·DB 마이그레이션을 추가하는 것은 아니다. 대상은 401이고 403 등 다른 HTTP 오류의 업무 정책을 함께 확정하지 않는다.

PortOneRefundCancellationGateway의 사전 GET/취소 POST와 PortOneRefundPaymentLookup의 결과 GET에서 401을 PgAuthenticationUnavailable로 변환한다. PgUnavailable의 하위 타입이므로 기존 Kafka 첫 송신·재송신·결과 조회의 미확정 처리와 일정을 그대로 따른다. 로그에는 예외 타입만 추가해 인증 장애를 식별할 수 있게 하며, 공급자 오류 본문/헤더/자격 증명을 예외 메시지나 업무 결과에 담지 않는다. 401 본문이 JSON이 아니어도 실패 근거로 해석하거나 파싱 오류로 바꾸지 않는다. [공식 API 명세의 UnauthorizedError](https://developers.portone.io/api/schema/v2/openapi.json)는 인증 정보가 올바르지 않은 경우를 401로 정의한다.

키 수정 후에도 새 PG 클라이언트의 정상 조회로 원결제·주문 연결·금액·통화·PG 거래와 전액 잔액·예상 취소 이력을 대조한다. 기존 attemptId/키·고정 본문·첫 송신 준비와 3시간 창을 유지하고 10초/30초/이후 1분 일정에 따라 재개한다. 잘못된 키를 반복 확인하는 PG 호출/로그 비용은 수용한다. 401만으로 환불 실패·권한 복구·새 시도를 만들지 않는다. 인증 설정 수정 자체를 금융 결과의 근거로 삼지 않는다.

반례: 앞선 취소는 성공했는데 결과 조회만 401일 수 있다. 정상 조회가 회복돼 전액 취소가 보이면 기존 금융 완료 계약으로 연결하고 POST하지 않는다. 부분 취소·예상 밖 이력·결제 불일치는 기존 사전 조건으로 보류/중단한다. 최초 준비 후 3시간이 지났다면 올바른 키로 바꿔도 자동 POST를 재개하거나 창을 초기화하지 않고 조회를 이어간다. 잘못된 상점/채널 설정으로 정상 조회가 실패하거나 다른 구매 자료가 반환돼도 검증을 통과하지 못한다. 운영 알림/UI와 나머지 HTTP 오류 분류는 후속 범위다.

검증 결과는 아래 실행별 기록을 따른다. 키 교체는 OCI의 HTTP 계약 픽스처와 새 클라이언트 조립으로 검증하며 실제 저장된 API 키를 일부러 잘못된 값으로 바꾸지 않는다. 실제 PG 환불 E2E와 구분한다.


검증: 사전 GET·취소 POST·결과 GET의 401이 식별되지 않는 Red 3건을 확인한 뒤 분류를 추가했다. 기존 B안의 업무 동작을 일부러 실패시키거나 새 정책으로 바꾸지 않았다. 초기에도 통과하던 인증 예외의 Kafka 미확정 처리와 함께, 최종 로컬 Payment 단위 253건·통합 소스 컴파일·bootJar가 통과했다. 원자료는 `runs/refund-auth-tdd-ep4o24t7/`의 red-junit/red-results.json·green.log·pre-oci-build.log·final-integration-compile.log다.

OCI full에서 **Payment 단위 253·통합 126건이 모두 실패·오류·건너뜀 0**으로 통과했다. 새 통합 5건은 잘못된 키로 사전 조회 401→새 클라이언트의 올바른 키로 같은 DB 접수 자동 재개, POST 401 뒤 동일 본문/키의 재송신, 취소는 완료됐지만 결과 조회만 401이었던 건의 조회 복구/추가 POST 없음, 3시간 뒤 키 수정에도 기한 유지/GET 가능, 인증 복구 뒤 부분 취소 발견 시 자동 POST 중단을 검증한다. 이 통합은 실제 DB·HTTP·재송신 스캔 서비스로 검증했으며 주기 타이머/Kafka의 기존 회귀도 전체 실행에 포함한다. 401에 따른 실패/복구 이벤트를 발행하지 않고 원 PAID·첫 준비·동일 실행을 보존했다.

소스 1,067개를 SHA-256 대조했고 실제 비밀값 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개와 ID/시작/재시작/건강 상태를 보존했으며 추가·잔여 Testcontainers/볼륨은 없다. 원격은 `/tmp/stove-refund-auth-check-PQrYmhH8/work`, 원자료는 `runs/refund-auth-oci-sf6_xxur/collected/results/full`의 소스/명령/로그/JUnit 및 preservation.json이다. 운영/테스트 소스는 최종 검증 해시와 같고 이후 변경은 문서뿐이다. Payment 외 모듈을 다시 검증한 것으로 표시하지 않으며, HTTP 계약 픽스처의 키 교체 검증을 실제 PG 환불 E2E로 확대하지 않는다. DB 스키마는 V23을 유지한다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --continue -Pstove.infraTest.maxParallel=1 --console=plain --no-daemon --max-workers=2
```


검증된 runtime JAR로 로컬 실제 주문 환경의 Payment만 갱신했다. 건강 상태는 UP·스키마 V23이며 기존 첫 송신/조회/재송신 설정을 유지한다. 실제 API/Webhook 자격 증명 파일의 해시가 적용 전후 같음을 확인했다. Auth·Catalog·Order·SSH PID와 본인 주문/결제 API 응답 전체를 보존했다. 기존 9,000원은 PAID·환불 실행 0건이고 실제 PG 취소 POST는 보내지 않았다. 공개 HTTPS 웹훅은 인증서 검증 성공·서명 없는 요청 401로 확인했다(이 검사는 PG 인증 복구 검증과 별개다). 근거는 runs/refund-auth-tdd-ep4o24t7/live-activation.json·activation.log·public-webhook-smoke.json이다.


## 2026-10-28 취소 POST 403 자동 중단 A안

사용자는 사전 조회는 정상인데 취소 POST가 403으로 거절된 경우, 해당 시도의 자동 취소 송신을 중단하고 운영자의 명시적 재개를 기다리는 A안을 선택했다. 결과 GET은 계속하며 403만으로 금융 실패·권한 복구·새 시도를 만들지 않는다. 이번 범위는 취소 POST의 403이다. 사전/결과 GET의 403은 취소 요청 거절 근거로 저장하지 않고 기존 조회 불가 동작을 유지하며 별도 정책의 미결 범위로 둔다. [공식 명세](https://developers.portone.io/api/schema/v2/openapi.json)는 ForbiddenError를 요청이 거절된 경우로 정의하며 추가 원인을 보장하지 않는다.

PortOneRefundCancellationGateway는 POST의 403을 RefundCancellationDispatch.FORBIDDEN으로 매핑한다. 오류 본문을 파싱하거나 자격 증명/원문 오류를 기록하지 않는다. RequestRefundCancellation은 해당 HTTP 소유자의 관측을 RefundCancellationHold에 저장하고, 현재 유효한 소유자라면 실행의 active hold와 POST 일정/임대 중단을 같은 짧은 트랜잭션에서 커밋한다. 그 뒤 결과 GET을 수행하므로 조회가 실패해도 중단은 보존된다. 같은 소유자의 재전달은 동일 관측이며, 새 소유자가 이미 진행하거나 금융 결과를 확정한 뒤 도착한 과거 403은 적용되지 않은 이력으로만 보존한다. 정상 동시 실행/재개와 늦은 결과의 덮어쓰기를 분리한다.

중단 중에는 새 스캔·Kafka 재전달·기존 내부 송신 호출 모두 POST하지 않고 조회를 이어간다. 키나 설정을 고친 것만으로 해당 중단을 자동 해제하지 않는다. 원래 Payment PAID·RefundExecution PENDING과 키/본문/첫 준비 시각/시도 이력은 유지한다. 기존 금융 완료/검증된 실패 근거가 확인되면 기존 결과 계약을 따르며, 중단을 금융 결과로 취급하지 않는다.

운영자 내부 유스케이스 RequestRefundCancellationResume는 요청 ID·attemptId·중단 ID·운영자 식별자·검토 사유를 입력받는다. 현재 중단과 원래 구매를 잠금 아래에서 대조하고, 최초 준비 후 3시간 미만이며 금융 완료/확정 실패·접수된 취소·부분 잔액 변경·다른 자동 중단이 없어야 한다. 중단 이력의 최초 재개 지시와 현재 중단 해제/작업 대상 등록을 원자 저장한다. HTTP를 이 트랜잭션 안에서 보내지 않고 다음 작업자 스캔에서 처리 가능한 시각으로 등록한다. 기존 재송신 단계를 초기화하지 않는다. 실제 POST 전에는 원결제/전액 잔액/예상 이력과 임대/원래 3시간을 다시 검사한다.

같은 요청 ID와 같은 본문은 처음 저장한 재개 접수 결과를 반환한다. 이후 다시 403이 발생해 새 중단이 생겨도 예전 재개 지시의 중복이 이를 해제하지 않는다. 같은 요청 ID의 다른 내용 또는 이미 재개한 중단을 새 요청 ID로 다시 재개하려는 지시는 거절한다. 재개 뒤 또 403이면 새로운 관측/중단 ID를 보존하고 새 운영 판단이 필요하다. 운영자 지시 자체가 성공을 보장하지 않으며 원래 키/본문/3시간 창을 바꿔 거절을 우회하지 않는다.

반례: 중단 중 조회로 전액 취소가 먼저 확인되면 재개를 거절한다. 운영자가 재개해도 이후 조회에서 부분 취소가 발견되면 실제 POST하지 않는다. 기한이 지나면 운영 지시도 새 3시간을 만들지 못한다. 중단 저장이나 재개 저장이 실패하면 상태/이력이 함께 롤백된다. 오래된 HTTP 응답과 예전 운영 지시가 현재 진행을 다시 중단하거나 새 중단을 잘못 해제하지 않도록 식별자를 대조한다.

V24는 중단/운영 재개 이력과 실행별 활성 중단 포인터, 소유자별 단일 관측·재개 요청 ID 유일성·시간/POST 일정 배타 제약을 추가한다. 이력은 실행을 FK로 참조하며 활성 포인터는 실행 잠금/서비스에서 같은 실행의 이력임을 대조한다. 순환 삭제 FK를 만들지 않는다. 과거 일반 오류에서 403을 추정해 채우지 않는다. 추가 운영 확인·고객 대기·재개 누락과 이력/재개 기능의 구현 비용을 수용한다. 운영자 인증/역할 검사는 후속 관리자 HTTP 경계의 책임이며 이번에 공개 관리자 API/UI를 추가하지 않는다. 현재는 신뢰된 내부 호출용 유스케이스다.

검증 결과는 아래 실행별 기록에 추가한다. 전체 실제 PG 환불 E2E·고객/관리자 HTTP·나머지 오류 분류와 장기 대기 운영 알림은 후속이다.


검증: 어댑터 1건·도메인 7건의 미구현 Red 8건을 확인한 뒤 구현했다. GET 403의 기존 조회 불가 처리와 내부 송신의 중단 저장→조회 순서를 회귀로 보강했고, 로컬 Payment 단위 264건·통합 소스 컴파일·bootJar가 통과했다. 원자료는 `runs/refund-forbidden-tdd-4f_lh_a6/`의 adapter-red-junit/domain-red-junit·all-red-results.json·pre-oci-build.log·corrected-compile.log다.

OCI full은 단위 264건 통과·통합 137건 중 1건 실패였다. 저장소 프록시의 추상 메서드에 callRealMethod를 호출한 롤백 픽스처 오류였다. 실제 EntityManager.persist/flush와 동일 트랜잭션의 삽입 확인 후 예외를 발생시키도록 수정하고, 프록시의 예외 타입 대신 주입한 원인과 최종 롤백 상태를 검증했다. 운영 코드는 바꾸지 않았다. corrected 전체 --rerun-tasks 실행에서 **Payment 단위 264·통합 137건이 모두 실패·오류·건너뜀 0**으로 통과했다. 두 실행의 소스/로그/JUnit을 구분해 보존하며 최초 픽스처 오류를 업무 Red로 계산하지 않는다.

새 통합 11건은 실제 DB/HTTP로 중단 내구성·송신 차단과 GET 유지, 명시적 재개와 같은 본문/키/원 준비, 재차 403 후 과거 재개 지시의 중복/변경 거절, 동시 재개, 금융 완료 뒤 재개 거절, 원래 3시간 경계, 재개 뒤 부분 취소의 미송신, 실제 중단 삽입/재개 flush 뒤 원자 롤백과 복구, 만료된 소유자의 늦은 403과 최신 성공 보존, 중단 커밋 뒤 조회 장애를 검증한다. Kafka/주기 작업자·기존 성공/실패/복구 관련 Payment 회귀도 전체 실행에 포함한다. 관리자 HTTP/역할 인증·실제 PG 환불 E2E나 다른 모듈 재검증으로 확대하지 않는다.

소스 1,074개를 SHA-256 대조했고 실제 비밀값 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개의 ID/시작/재시작/건강 상태를 보존했으며 추가·잔여 Testcontainers/볼륨은 없다. 원격은 `/tmp/stove-refund-forbidden-check-5nzORkMy/work`, 원자료는 `runs/refund-forbidden-oci-t90dzc63/collected/results/{full,corrected}` 및 preservation.json이다. 운영/테스트 소스는 최종 검증 해시와 같고 이후 변경은 문서뿐이다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --continue -Pstove.infraTest.maxParallel=1 --rerun-tasks --console=plain --no-daemon --max-workers=2
```


검증된 runtime JAR로 로컬 실제 주문 환경의 Payment만 갱신하고 V24를 적용했다. 건강 상태는 UP이고 기존 첫 송신/조회/재송신 설정을 유지한다. 실제 자격 증명 파일은 적용 전후 같은 해시이며 Auth·Catalog·Order·SSH PID와 본인 주문/결제 API 응답 전체를 보존했다. 기존 9,000원은 PAID·환불 실행 0건으로 실제 PG 취소 POST를 보내지 않았다. 공개 HTTPS 웹훅의 인증서 검증과 서명 없는 요청 401도 확인했다. 근거는 runs/refund-forbidden-tdd-4f_lh_a6/live-activation.json·activation.log·public-webhook-smoke.json이다. 운영자 재개는 내부 유스케이스이며 관리자 HTTP/권한 인증은 후속이다.


## 2026-10-28 조회 GET 404 뒤 자동 재개 B안

사용자는 조회 GET이 404인 동안에는 미확정으로 유지하고, 동일 구매의 정상 조회와 기존 송신 조건이 회복되면 별도 운영 해제 없이 이어가는 B안을 선택했다. 기존 일반 조회 불가/미확정 동작에 이미 포함된 업무 흐름을 유지하며, 이번에는 조회 404를 식별하는 PgPaymentNotFound 분류와 명시적 회귀를 추가한다. [공식 명세](https://developers.portone.io/api/schema/v2/openapi.json)는 조회와 취소 모두의 PaymentNotFoundError를 결제 건이 존재하지 않는 경우로 정의한다. 원인이나 일시성을 보장하지 않으므로, 404가 사라질 것이라고 가정해 금융 결과를 확정하지 않는다.

대상은 사전 GET과 결과 GET이다. PortOneRefundCancellationGateway/PortOneRefundPaymentLookup은 GET의 404를 PgUnavailable 하위의 PgPaymentNotFound로 변환한다. 공급자의 오류 본문을 파싱하거나 예외/업무 결과에 복사하지 않는다. Kafka 첫 송신과 기존 GET/POST 작업자는 미확정 처리와 내구 일정으로 이어가며, 조회가 실패한 회에는 POST하지 않는다. 취소 POST의 404는 이번 정책에서 조회 오류로 분류하지 않고 기존 일반 미확정 경로를 유지한다. POST 404의 업무 정책은 다음 결정이다.

정상 HTTP 상태만으로 재개하지 않는다. 원래 Payment의 PAID/구매 근거와 같은 결제·주문 연결·금액·통화·PG 거래/시각, 원액=취소액=예상 잔액, 예상 취소 이력을 다시 확인해야 한다. 같은 attemptId/키·고정 본문·첫 준비 시각·3시간 창과 10초/30초/이후 1분의 재송신 일정은 유지한다. 404는 실패·권한 복구·새 실행·운영 중단 근거가 아니다. 계속 조회되지 않는 건의 고객 대기와 PG 조회/로그 비용은 남는다.

반례: 정상 조회가 돌아와도 다른 구매이면 POST하지 않는다. 이미 전액 취소됐으면 기존 조회 완료로 연결하고 추가 POST하지 않는다. 부분 취소는 기존 잔액 변경 중단을 적용한다. 최초 준비 후 3시간이 지나면 조회만 가능하며 창을 다시 시작하지 않는다. 기존 취소 POST 403 중단이 있으면 GET 404의 회복이 이를 해제하지 않고 명시적 운영 재개를 기다린다. 결제 성공 기록을 지우거나 조회 부재로 미결제를 추정하지 않는다.

DB 스키마/활성화 설정은 V24와 기존 첫 송신·조회·재송신 플래그를 유지한다. 운영 해제·새 상태나 HTTP API를 추가하지 않는다. 검증 결과는 아래 실행별 기록에 추가한다. 취소 POST 404와 나머지 HTTP 오류·장기 대기 운영 정책, 고객/관리자 HTTP 및 실제 PG 환불 E2E는 후속이다.


검증: 사전/결과 GET 404가 구분되지 않는 Red 2건을 확인한 뒤 분류를 추가했다. 기존 B안의 업무 동작을 일부러 실패시키지 않았으며, POST 404를 조회 오류로 분류하지 않는 경계와 Kafka의 기존 미확정 처리를 회귀로 고정했다. 최종 로컬 Payment 단위 268건·통합 소스 컴파일·bootJar가 통과했다. 원자료는 `runs/refund-lookup404-tdd-q205utzl/`의 red-junit/red-results.json·pre-oci-build.log다.

OCI full에서 **Payment 단위 268·통합 143건이 모두 실패·오류·건너뜀 0**으로 통과했다. 새 통합 6건은 사전 조회 404 뒤 동일 구매 확인과 같은 키/본문/첫 준비의 자동 재개, 이미 취소됐지만 결과 조회가 404였던 건의 조회 회복/추가 POST 없음, 3시간 뒤 정상 조회의 미송신/GET 유지, 정상 HTTP에 다른 구매가 반환될 때의 보류 후 올바른 구매로 재개, 부분 취소의 자동 중단, 기존 취소 POST 403 중단을 조회 회복이 해제하지 않음을 실제 DB·HTTP·재송신 스캔 서비스로 검증했다. 기존 Kafka/주기 타이머와 운영 재개 회귀도 전체 실행에 포함한다. 조회 부재로 금융 실패/권한 복구 이벤트를 발행하지 않고 원 PAID·동일 실행·원래 준비/본문을 보존했다.

소스 1,075개를 SHA-256 대조했고 실제 비밀값 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개와 ID/시작/재시작/건강 상태를 보존했으며 추가·잔여 Testcontainers/볼륨은 없다. 원격은 `/tmp/stove-refund-lookup404-check-3X1ZT8zV/work`, 원자료는 `runs/refund-lookup404-oci-1cis4_cs/collected/results/full`의 소스/명령/로그/JUnit 및 preservation.json이다. 운영/테스트 소스는 최종 검증 해시와 같고 이후 변경은 문서뿐이다. 스키마/설정은 V24와 기존 활성화를 유지한다. Payment 외 모듈 재실행·취소 POST 404의 정책 확정·실제 PG 환불 E2E로 확대하지 않는다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --continue -Pstove.infraTest.maxParallel=1 --console=plain --no-daemon --max-workers=2
```


검증된 runtime JAR로 로컬 실제 주문 환경의 Payment만 갱신했다. 건강 상태는 UP·스키마 V24이며 기존 첫 송신/조회/재송신 활성화를 유지한다. 실제 자격 증명 파일의 해시, Auth·Catalog·Order·SSH PID와 본인 주문/결제 API 응답 전체를 보존했다. 기존 9,000원은 PAID·환불 실행 0건이며 실제 PG 취소 POST를 보내지 않았다. 공개 HTTPS 웹훅은 인증서 검증 성공·서명 없는 요청 401로 확인했다. 근거는 runs/refund-lookup404-tdd-q205utzl/live-activation.json·activation.log·public-webhook-smoke.json이다.


## 2026-10-28 취소 POST 404 뒤 자동 재개 B안

사용자는 사전 GET에서 원 구매를 확인했지만 취소 POST가 404를 반환하는 경우에도 미확정으로 유지하고, 다음 회의 정상 조회/기존 송신 조건을 통과하면 자동 재개하는 B안을 선택했다. 실제로 관측한 제공사 장애 사례가 아닌 가정에 대한 정책이다. [공식 명세](https://developers.portone.io/api/schema/v2/openapi.json)는 두 API의 PaymentNotFoundError를 정의하지만 서로 다른 결과의 원인이나 일시성을 보장하지 않는다. 조회 GET과 취소 POST가 같은 결제 ID·상점 ID를 사용하는 현재 검증은 유지한다.

기존 일반 PgUnavailable의 미확정/내구 재시도 동작을 유지하고, POST의 404만 PgCancellationNotFound로 구분한다. GET의 404는 기존 PgPaymentNotFound다. 원문 오류를 파싱하거나 예외/로그에 복사하지 않는다. 첫 Kafka 소비자는 미확정으로 종료하며 POST 재송신은 기존 별도 일정으로만 수행한다. 404를 금융 실패·권한 복구·운영자 중단 또는 새 실행의 근거로 삼지 않는다.

매번 같은 구매·원액=취소액=예상 잔액·예상 취소 이력을 다시 확인해야 한다. 같은 attemptId/키·고정 본문·최초 준비 시각·원래 3시간 미만을 유지하며 한 회 종료 후 10초/30초/이후 1분을 기다린다. 반복 404도 창을 초기화하지 않는다. 정상 조회의 PAID는 앞선 요청 부재를 증명하지 않으므로 중복 방지는 기존 제공사 멱등 계약에 의존한다.

반례: 다음 조회가 이미 전액 취소를 확인하면 금융 완료로 연결하고 추가 POST하지 않는다. 다른 구매나 부분 취소이면 재송신하지 않는다. 3시간이 지나면 GET만 유지한다. 후속 POST 403은 기존 운영 중단을 만들며, 앞선 404나 정상 조회가 이를 해제하지 않는다. 반복 조회/재송신·로그 비용, 해결되지 않는 설정 문제의 고객 대기와 최대 3시간 내 반복 호출 비용을 수용한다. 향후 실제로 반복되는 GET/POST 불일치가 관측되면 원인을 조사하고 운영 중단 정책의 필요성을 다시 검토한다. 이번에 새 자동 중단 조건/경보·관리자 HTTP를 임의로 추가하지 않는다.

DB/설정은 V24와 기존 첫 송신/조회/재송신 플래그를 유지한다. 식별 Red와 기존 B 동작의 회귀를 구별하며, 검증 결과는 아래 실행별 기록에 추가한다. 나머지 HTTP 오류·장기 대기 운영 기준과 실제 PG 환불 E2E는 후속이다.


검증: POST 404가 전용 분류로 식별되지 않는 Red 1건을 확인한 뒤 PgCancellationNotFound를 연결했다. 기존 B안 업무 흐름은 이미 일반 미확정 경로에 있으므로 일부러 실패시키지 않았다. Kafka 첫 소비자가 이 분류를 미확정으로 처리하는 회귀도 추가했다. 로컬 Payment 단위 269건·통합 소스 컴파일·bootJar가 통과했다. 원자료는 `runs/refund-post404-tdd-0mfmnlca/`의 red-junit/red-results.json·pre-oci-build.log다.

OCI full에서 **Payment 단위 269·통합 149건이 모두 실패·오류·건너뜀 0**으로 통과했다. 새 통합 6건은 정상 사전 GET 뒤 POST 404 반복의 10/30/60초 간격·동일 본문/키/첫 준비 유지 후 성공, 이후 전액 취소 조회의 미중복 완료, 부분 취소의 자동 중단, 다른 구매 조회의 미송신 뒤 원 구매 회복, 원래 3시간 경과/GET 유지, 후속 403 중단의 명시적 운영 재개 필요를 실제 MySQL·HTTP·재송신 스캔으로 검증했다. 기존 Kafka/타이머·동시/롤백/중단 회귀도 같은 전체 실행에 포함했다. POST 404를 금융 실패/권한 복구로 만들지 않고 원결제 PAID를 보존했다.

소스 1,076개를 SHA-256 대조했고 실제 비밀값 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개와 ID/시작/재시작/건강 상태를 보존했으며 추가·잔여 Testcontainers/볼륨은 없다. 원격은 `/tmp/stove-refund-post404-check-POzTUeKl/work`, 원자료는 `runs/refund-post404-oci-_0gml1ha/collected/results/full`의 소스/명령/로그/JUnit 및 preservation.json이다. 최종 검증 뒤 운영/테스트 소스는 바꾸지 않았고 문서만 보완했다. V24와 기존 설정을 유지한다. HTTP는 계약 픽스처이며 실제 PG 취소 장애 관측/실제 PG 환불 E2E 또는 다른 모듈 재검증이 아니다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --continue -Pstove.infraTest.maxParallel=1 --console=plain --no-daemon --max-workers=2
```

검증된 runtime JAR로 로컬 실제 주문 환경의 Payment만 갱신했다. 건강 상태는 UP·스키마 V24이며 기존 첫 송신/조회/재송신 활성화를 유지한다. 실제 자격 증명 파일의 해시, Auth·Catalog·Order·SSH PID와 본인 주문/결제 API 응답 전체를 보존했다. 기존 9,000원은 PAID·환불 실행 0건이며 실제 PG 취소 POST를 보내지 않았다. 공개 HTTPS 웹훅은 인증서 검증 성공·서명 없는 요청 401로 확인했다. 근거는 runs/refund-post404-tdd-0mfmnlca/live-activation.json·activation.log·public-webhook-smoke.json이다.


## 2026-10-28 조회 GET 403 뒤 자동 재개 B안

사용자는 서버의 결제 조회 GET이 403으로 거절되면 미확정으로 유지하고, 정상 조회와 기존 송신 조건이 회복되면 별도 건별 운영 재개 없이 이어가는 B안을 선택했다. [공식 명세](https://developers.portone.io/api/schema/v2/openapi.json)는 조회의 ForbiddenError를 요청 거절로 정의한다. 정확한 원인이나 일시성·환불 성공/실패를 403만으로 확정하지 않는다. 실제 제공사 장애 관측이 아닌 가정에 대한 정책이다.

대상은 취소 직전 사전 GET과 결과 확인 GET이다. 두 어댑터에서 403을 PgUnavailable 하위 PgPaymentLookupForbidden으로 식별하며 공급자 오류 본문을 파싱/복사하지 않는다. 기존 일반 미확정 동작과 내구 조회/재송신 일정을 유지한다. 첫 Kafka 소비자가 이 예외 때문에 취소 POST를 반복하지 않는다. 조회 403을 취소 POST의 FORBIDDEN 관측으로 바꾸거나 운영 중단/금융 실패/권한 복구/새 실행을 만들지 않는다.

재송신 직전 GET이 403인 회에는 POST하지 않는다. 정상 HTTP 상태만으로 재개하지 않고 동일 원 구매·주문 연결·금액/통화·원결제 PG 거래/시각·원액=취소액=예상 잔액·예상 취소 이력을 다시 확인해야 한다. 같은 attemptId/키·고정 본문·첫 준비 시각·원래 3시간 미만 및 종료 후 10/30/60초의 송신 일정을 보존한다. 결과 GET은 기존 최초 준비 후 5/15/35초·이후 1분과 웹훅 조기 조회를 유지한다.

반례: 이미 전액 취소됐으면 GET으로 금융 완료를 기록하고 추가 POST하지 않는다. FAILED 응답을 받았어도 추가 GET이 403이면 아직 실패/권한 복구를 요청하지 않고, 정상 원 구매 전액 미취소·우리 실패 이력·다른 진행 취소 없음까지 확인한 뒤 기존 실패 계약으로 이어간다. 다른 구매·부분 취소는 자동 POST를 허용하지 않는다. 최초 준비 후 3시간 경과는 조회가 회복돼도 새 창을 만들지 않으며 GET만 유지한다. 기존 취소 POST 403 중단은 조회 403/정상 조회가 해제하지 않고 명시적 운영 재개를 기다린다.

A/B 모두 GET을 유지하므로 조회 호출/로그·문제가 지속될 때의 고객 대기 비용은 공통이다. B는 건별 운영 확인/재개 구현·운영 비용을 추가하지 않는 대신, 설정 복구 후 조건을 통과한 대기 건이 자동으로 이어질 수 있음을 수용한다. 이미 진행 중인 PG 처리를 조회 오류로 취소할 수는 없으며, 정상 조회의 PAID도 이전 요청 부재를 증명하지 않는다. 기존 멱등 키/본문과 제공사 계약에 의존한다. 이번에 경보·새 운영 중단 사유·관리자 HTTP를 임의로 추가하지 않는다.

스키마/플래그는 V24와 기존 첫 송신/조회/재송신을 유지한다. B의 기존 업무 동작과 신규 오류 식별의 Red를 구분한다. 검증 결과는 아래 실행별 기록에 추가한다. 나머지 HTTP 오류·장기 대기 운영 기준·고객/관리자 HTTP 및 실제 PG 환불 E2E는 후속이다.


검증: 사전/결과 GET 403이 전용 오류로 구분되지 않는 Red 2건을 확인한 뒤 PgPaymentLookupForbidden을 연결했다. B의 업무 흐름은 이미 일반 미확정 경로에 있으므로 일부러 실패시키지 않았다. Kafka 첫 소비자의 미확정 처리 회귀도 추가했다. 로컬 Payment 단위 271건·통합 소스 컴파일·bootJar가 통과했다. 원자료는 `runs/refund-get403-tdd-a6d6edc_/`의 red-junit/red-results.json·pre-oci-build.log다.

OCI full에서 **Payment 단위 271·통합 156건이 모두 실패·오류·건너뜀 0**으로 통과했다. 새 통합 7건은 반복 사전 GET 403의 미송신/같은 요청 자동 재개, 결과 GET 403의 내구 조회 일정 유지/회복 후 미중복 완료, FAILED 응답+조회 403의 실패 이벤트 보류 뒤 정상 추가 증거로 1회 실패 확인, 원래 3시간 경과의 미송신/GET 유지, 부분 취소 중단, 다른 구매의 미송신 뒤 올바른 구매로 재개, 기존 취소 POST 403 중단의 미해제를 실제 MySQL·HTTP·조회/재송신 작업자로 검증했다. 기존 Kafka/타이머·동시/롤백 회귀도 같은 전체 실행에 포함한다. 조회 거절로 금융 실패/복구를 추정하지 않고 원결제 PAID·같은 실행/키/본문/첫 준비를 보존했다.

소스 1,077개를 SHA-256 대조했고 실제 비밀값 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개와 ID/시작/재시작/건강 상태를 보존했으며 추가·잔여 Testcontainers/볼륨은 없다. 원격은 `/tmp/stove-refund-get403-check-aIbmzxab/work`, 원자료는 `runs/refund-get403-oci-u43efztz/collected/results/full`의 소스/명령/로그/JUnit 및 preservation.json이다. 최종 검증 뒤 운영/테스트 소스는 바꾸지 않았고 문서만 보완했다. V24와 기존 설정을 유지한다. HTTP와 실패 후속 경계는 계약 픽스처이며 실제 PG 조회 거절 관측/실제 PG 환불·License 복구 E2E 또는 다른 모듈 재검증이 아니다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --continue -Pstove.infraTest.maxParallel=1 --console=plain --no-daemon --max-workers=2
```


검증된 runtime JAR로 로컬 실제 주문 환경의 Payment만 갱신했다. 건강 상태는 UP·스키마 V24이며 기존 첫 송신/조회/재송신 활성화를 유지한다. 실제 자격 증명 파일의 해시, Auth·Catalog·Order·SSH PID와 본인 주문/결제 API 응답 전체를 보존했다. 기존 9,000원은 PAID·환불 실행 0건이며 실제 PG 취소 POST를 보내지 않았다. 공개 HTTPS 웹훅은 인증서 검증 성공·서명 없는 요청 401로 확인했다. 근거는 runs/refund-get403-tdd-a6d6edc_/live-activation.json·activation.log·public-webhook-smoke.json이다.


## 2026-10-28 취소 POST 400 INVALID_REQUEST 자동 중단 A안

사용자는 취소 POST가 HTTP 400과 정확한 INVALID_REQUEST 타입으로 입력 오류를 알리면 자동 재송신을 중단하고, GET과 운영자 명시적 재개로 이어가는 A안을 선택했다. [공식 명세](https://developers.portone.io/api/schema/v2/openapi.json)는 InvalidRequestError를 허가되지 않은 값/잘못된 형식을 포함하는 유효하지 않은 입력으로 정의한다. 입력 문제는 같은 본문 재송신만으로 해결되지 않을 수 있으므로 원인 확인·건별 재개·고객 대기 비용을 수용한다. 실제 제공사 오류 관측이 아닌 가정에 대한 정책이다.

범위는 POST의 상태 400과 정상 JSON의 정확한 type=INVALID_REQUEST 조합이다. RefundCancellationDispatch.INVALID_REQUEST는 거절 관측이며 금융 실패/취소 접수 영수증이 아니다. 원문 message를 저장/로그에 복사하지 않는다. 미지의 타입·누락/비문자열 type·공백이 붙은 다른 값·깨진 JSON·중복 키·후행 JSON은 이 중단으로 분류하지 않고 기존 일반 미확정 경로를 유지한다. 사전/결과 GET의 400은 취소 거절 관측이 아니다. 나머지 오류의 업무 정책은 이번 결정에 포함하지 않는다.

기존 RefundCancellationHold와 RequestRefundCancellationResume를 재사용한다. 새 중단 사유 PG_REQUEST_INVALID와 현재 소유자의 관측/활성 중단은 짧은 트랜잭션에서 원자적으로 저장하고, 커밋 뒤 결과 GET을 수행한다. 이어지는 GET 장애도 이미 저장한 중단을 지우지 않는다. 첫 Kafka/동일 시도 재송신/수동 내부 송신 모두 활성 중단을 지킨다. 과거 소유자의 늦은 관측은 감사 이력만 남기며 완료/새 소유자의 상태를 중단하지 않는다. 같은 소유자의 중복 관측은 새 중단을 만들지 않는다.

운영 재개는 현재 중단 ID·미확정 상태·원래 3시간 미만·접수/완료/부분 취소 등 새 근거 부재를 대조해 다음 작업 대상으로만 등록한다. 같은 재개 지시는 최초 접수로 멱등 처리하고 예전 지시가 새 중단을 해제하지 않는다. 실제 POST 전 정상 구매/전액 잔액/예상 이력을 다시 검증한다. 같은 attemptId/키·고정 본문·첫 준비·원래 기한과 기존 일정을 보존하며 재개 자체는 POST나 환불 완료가 아니다. GET이 전액 취소를 확인하면 기존 금융 완료로 연결하고 추가 POST하지 않는다. 3시간 뒤에는 운영 재개도 새 창을 만들지 않고 GET만 유지한다.

반례와 한계: 본문 자체가 잘못됐다면 같은 본문으로 재개한 뒤 다시 같은 오류/새 중단이 생길 수 있다. 이번에는 본문 수정·새 키/시도·3시간 초기화·자동 실패/이용 권한 복구를 허용하지 않는다. 본문 정정은 별도 후속 계약이며, 검증된 FAILED와 실제 복구 뒤에만 새 시도를 허용하는 기존 조건을 400으로 우회하지 않는다. 운영자가 PG 설정/지원 여부를 바로잡은 경우에도 현재 중단을 명시적으로 재개해야 한다. 관리자 HTTP·역할 인증·화면·경보는 후속이며 내부 신뢰 유스케이스와 구분한다.

V25는 기존 중단 관측 CHECK에 PG_REQUEST_INVALID를 허용하고 관측 시각 조건을 유지한다. V24 및 과거 마이그레이션은 수정하지 않고 기존 403 관측/활성 중단/운영 재개 이력을 그대로 보존한다. 새 테이블/열/설정 플래그 없이 기존 첫 송신/조회/재송신 설정을 유지한다. 검증 결과는 아래 실행별 기록에 추가한다. 나머지 오류/장기 대기 운영 기준과 실제 PG 환불 E2E는 후속이다.


검증: 어댑터의 입력 오류 중단 관측과 서비스의 중단 저장/커밋이 없는 Red 2건을 확인한 뒤 구현했다. 로컬 Payment 단위 276건·통합 소스 컴파일·bootJar가 통과했다. 원자료는 `runs/refund-invalid400-tdd-60edk1d3/`의 red-junit/red-results.json·pre-oci-build.log·corrected-build.log다.

OCI full은 단위 276건 통과·통합 166건 중 마이그레이션 픽스처 1건 실패였다. V24 행 복사에서 MySQL 자동 생성 열 pending_payment_guard를 직접 입력한 테스트 오류이며, 일반 열만 복사하고 생성 열은 DB가 계산하도록 수정했다. 운영 코드와 V25 마이그레이션은 최초 OCI 이후 바꾸지 않았다. corrected 전체를 **--rerun-tasks**로 재실행해 **Payment 단위 276·통합 166건 모두 실패·오류·건너뜀 0**으로 통과했다. 최초 실패를 성공 실행으로 덮어쓰지 않고 각 소스/로그/JUnit을 따로 보존했다.

새 통합 10건은 재생성한 송신기의 중단 준수와 GET 유지, 같은 본문/키/첫 준비를 지키는 명시적 재개와 반복 오류의 새 중단/예전 지시 멱등성, 중단 저장 뒤 조회 장애, 원래 3시간 경과, GET의 금융 완료와 추가 POST/재개 금지, 재개 뒤 부분 잔액 차단, 실제 삽입/flush 뒤 중단/실행 변경의 함께 롤백, 만료 소유자의 늦은 400이 완료를 다시 중단하지 않음, 미지의 400과의 분류 경계, V24의 활성 403 중단·재개 이력/실행 행 전체를 V25가 보존하고 미지 사유를 DB에서 거절함을 검증했다. 기존 Kafka/타이머·GET/403 중단·실패 추가 증거와 동시/롤백 회귀도 같은 전체 실행에 포함한다.

소스 1,078개를 SHA-256 대조했고 실제 비밀값 포함은 0건이다. 공유 컨테이너 22개·볼륨 57개와 ID/시작/재시작/건강 상태를 보존했으며 추가·잔여 Testcontainers/볼륨은 없다. 원격은 `/tmp/stove-refund-invalid400-check-tap6TOjL/work`, 원자료는 `runs/refund-invalid400-oci-3bpzduhu/collected/results/{full,corrected}`의 소스/명령/로그/JUnit·preservation.json과 correction-review.json이다. 최종 검증 뒤 운영/테스트 소스는 바꾸지 않았고 문서만 보완했다. HTTP는 계약 픽스처이며 실제 PG 입력 오류 관측/실제 PG 환불·License 복구 E2E 또는 다른 모듈 재검증이 아니다. 본문 정정·관리자 HTTP/역할 인증·나머지 오류/운영 기준은 후속이다.

```sh
./gradlew :apps:payment:test :apps:payment:integrationTest --continue -Pstove.infraTest.maxParallel=1 --rerun-tasks --console=plain --no-daemon --max-workers=2
```


검증된 runtime JAR로 로컬 실제 주문 환경의 Payment만 갱신하고 V25를 적용했다. 건강 상태는 UP이며 기존 첫 송신/조회/재송신 활성화를 유지한다. 실제 자격 증명 파일의 해시, Auth·Catalog·Order·SSH PID와 본인 주문/결제 API 응답 전체를 보존했다. 기존 9,000원은 PAID·환불 실행 0건이며 실제 PG 취소 POST를 보내지 않았다. 공개 HTTPS 웹훅은 인증서 검증 성공·서명 없는 요청 401로 확인했다. 근거는 runs/refund-invalid400-tdd-60edk1d3/live-activation.json·activation.log·public-webhook-smoke.json이다.


## 2026-10-28 고객 환불 API와 새 TEST 결제 전체 검증 A안

사용자는 새 TEST 결제를 만들어 4시간 이내 자동 환불 흐름을 먼저 검증하는 A안을 선택했다. 보존 중인 기존 9,000원 결제는 이미 4시간이 지나 심사 경로가 필요하므로 자동 허용으로 취급하지 않는다. 우선 고객 접수/조회 API와 License·Download 검증 환경을 연결하고, 새 결제의 휴대폰 승인이 준비되면 안내한다. 관리자 승인/반려·재시도 HTTP·정산 조정은 별도 후속이다.

고객 계약: MEMBER JWT sub로 POST/GET /api/v1/orders/{orderNo}/refund를 제공한다. 금액/통화는 원주문 전액을 사용하며 입력 본문은 사용하지 않는다. 타인의 주문·없는 주문·조회할 환불 없음은 404이며 소유자를 먼저 대조한다. 결제 전 접수는 409다. 기존 RequestRefund의 주문 잠금/동일 주문 단일 접수/최초 자격과 Outbox 원자성을 재사용하고, 반복 POST가 새 시도를 만들지 않는다. GET은 읽기 전용이며 원래 PAID를 변경하지 않는다.

응답은 refundId/orderNo/amount/currency/status/receivedAt/completedAt이다. 내부 지급·정지·PG·복구 대기는 PROCESSING으로 묶고 PENDING_REVIEW/COMPLETED/FAILED를 별도로 제공한다. FAILED는 실제 권한 복구 확인 뒤의 기존 상태이며 PG의 FAILED 응답만으로 표시하지 않는다. PG 원문/자격 증명·내부 실행 연결값은 노출하지 않는다. POST는 대기 중 202+조회 Location, 이미 최종인 기존 접수는 200이며 접수 응답을 PG 완료로 표현하지 않는다. GET은 200이고 두 응답 모두 no-store다. 접수 커밋 뒤 최신 읽기가 실패해도 같은 주문 재접수/조회로 회복할 수 있다.

ReadRefund와 API 독립 읽기 모델 RefundView를 추가하며 기존 요청 서비스/DB 스키마를 유지한다(Order V19, Payment V25). 기본 거절 보안 설정에는 정확한 두 경로만 MEMBER로 허용한다. 최초 구현 검증과 실제 PG 결제/환불 전체 검증은 분리해 실행별 기록을 아래에 추가한다. Catalog 출시 입력과 테스트 ZIP은 검증 픽스처이며 실제 Studio/Review 업무 완료를 대신하지 않는다.


고객 API 검증: 조회 서비스/보안 경로의 Red 4건을 확인했다. 로컬 Order 단위 282건·통합 컴파일·bootJar가 통과했다. 첫 통합 컴파일에서 테스트가 존재하지 않는 Order.now()를 호출한 오류를 Instant의 마이크로초 시각으로 고쳤으며 업무 Red에 포함하지 않는다. OCI full은 단위 282 통과·통합 95 중 4건 실패였다. 의도한 신규 OpenAPI 스냅샷, 만료 JWT fixture의 issuedAt/expiresAt 순서, MANDATORY Outbox spy 스텁의 트랜잭션 누락과 그 미완성 스텁의 다음 테스트 전파였다. 테스트 설정을 수정하고 schema-update 선별 1건으로 명세를 생성했다. 이 실행의 단위 XML은 이전 실행 자료이며 단위를 재실행한 수치로 세지 않는다. 명세 diff에서 기존 경로/스키마가 그대로이고 신규 환불 경로 1개(GET/POST)와 응답 스키마 2개만 추가됨을 대조했다.

corrected 전체 **--rerun-tasks**에서 **Order 단위 282·통합 95건 모두 실패·오류·건너뜀 0**으로 통과했다. 새 HTTP 통합 8건은 실제 JWT/권한·타인/없는 주문과 미접수 404·서버 전액/고객 상태·중복/동시 단일 접수/Outbox·결제 전 거절·4시간 초과 심사 대기·Outbox 실제 기록 뒤 실패의 전체 롤백과 같은 접수 회복을 검증한다. 운영 코드는 최초 OCI 이후 변경하지 않았다. 원자료는 runs/refund-customer-api-tdd-_9bfod_b 및 runs/refund-customer-api-oci-yd50q5ee/collected/results/{full,schema-update,corrected}다. 소스 1,085개 대조·실제 비밀값 포함 0·공유 컨테이너 22개/볼륨 57개와 상태 보존·추가/잔여 Testcontainers/볼륨 없음. 원격은 /tmp/stove-refund-customer-api-check-aZZtiqhD/work다. Payment/License/Download 전체 회귀 재실행 또는 실제 PG 환불 완료로 확대하지 않는다.

검증된 Order runtime JAR를 적용했다. 건강 상태 UP·스키마 V19를 유지하고 적용 전후 기존 9,000원 본인 주문/결제 API 응답 전체가 같았다. 기존 환불 행은 0이며 새 환불 GET은 본인 미접수 404다. Auth·Catalog·Payment·SSH PID와 실제 PG 자격 증명 파일의 해시를 보존했다. 근거는 runs/refund-customer-api-tdd-_9bfod_b/live-activation.json이다.

## 2026-10-28 새 TEST 주문의 실제 자동 전액 환불 검증 완료

새 주문 b1898f83-e679-4c32-9590-452e35a53742 / 결제 536805b3-2d66-4b0e-98fd-458e12b11103의 9,000원 TEST 결제로 사용자 A안을 검증했다. 사용자의 휴대폰 승인 뒤에도 포트원/업무 상태는 READY/PENDING이었다. 기존 결제창에서 토스페이 가맹점 테스트용 PAY_APPROVED 리다이렉트와 이번 결제 번호를 확인하고 ‘이동하기’를 눌렀다. 그 후 실제 PG PAID와 Payment/Order PAID를 확인한 뒤에만 고객 환불 API를 호출했다. 다른 주문/새 결제를 만들거나 승인 시각·자격을 변경하지 않았다. 버튼 실행 근거는 runs/refund-live-obe63z8s/test-approval-final-step.json이다.

실제 Auth PKCE → 공개 Catalog 조회 → Order/Outbox/Kafka → SDK TEST 승인 → 실제 License 지급/ACTIVE → 실제 ZIP GET/해시 대조 → 고객 환불 POST와 중복 POST → License 실제 정지 → Payment의 PG 취소 → Order 환불 COMPLETED → Library SUSPENDED/Download 신규 URL 403을 연결했다. 접수 두 번은 202와 같은 refundId/receivedAt을 반환했다. 환불 ID는 e0e11b4c-9363-4e6a-a92f-e290e1cc7f05, 시도 ID는 8688b733-f60a-403f-9203-4634945b3ea9다.

최종 PG는 CANCELLED·전액 9,000원·SUCCEEDED 취소 1건이다. Order 시도와 Payment 실행은 SUCCEEDED·1건이며 고객 환불은 COMPLETED, 근거는 PG_RESPONSE이고 조사 필요 표시가 없다. PG 취소 ID와 DB 성공 취소 ID가 일치하고 고정 본문의 amount/currentCancellableAmount는 모두 9,000원이다. 자동 POST/GET 일정은 종료됐고 활성 운영 중단은 없다. 실제 정지 완료 이벤트 ID가 Order의 정지 확인과 Payment 입력에 동일하게 연결됐다. 보유권 복구 기록은 0건이다.

UTC 기준 접수 07:00:34.999077 → License 정지 07:00:36.814689 → Payment 접수 07:00:41.107214 → 최초 PG 준비 07:00:42.667716 → 금융 확인/Order 완료 07:00:44.099624를 대조했다. 한 사례의 실행 기록이며 처리량/지연 성능 보장으로 확대하지 않는다. 관련 Order/Payment/License Outbox 8건은 모두 SENT이며 동일 유형의 실행 요청·정지/완료를 중복 생성하지 않았다. 환불된 새 주문과 결제의 원래 PAID/구매 응답 전체를 보존했고, 별도로 보존하던 기존 9,000원 결제도 PAID·환불 미접수 404로 유지했다.

초기 검증 스크립트는 금융 완료·License 정지·Download 403과 기존 URL의 환불 직후 200까지 확인한 뒤 읽기 전용 DB 보고서에서 실패했다. license_restoration에 없는 order_no를 사용해 외부 쿼리의 열이 모호해진 오류다. lr.refund_id로 보고서 조인을 수정하고, 결제/환불 POST를 추가 실행하지 않은 읽기 전용 복구 검증으로 DB/API/PG를 다시 대조했다. 초기 코드/오류와 최종 결과를 따로 보존했다. 앱 운영 코드 변경이나 새로운 환불 시도는 없었다.

기존에 고객 API가 발급한 URL 자체의 만료 후 GET은 초기 관측 프로세스가 종료돼 확인하지 못했다(해당 URL을 디스크에 저장하지 않았다). 이 항목을 성공으로 기록하지 않는다. 별도 S3 서명 만료 제어 검사에서 같은 실제 저장소/읽기 계정의 만료 전 200·만료 후 403을 확인했으며, 고객 URL 발급·License 상태 변경과 구분한다. 신규 고객 Download URL 403은 현재 실제 API로 다시 확인했다. 결과의 previous_url_after_expiration=null과 storage_expiry_control을 구분해서 읽는다.

원자료는 runs/refund-live-obe63z8s/e2e-result.json·completed-current-check.json·database-proof.json·outbox-proof.json·final-verification.log·initial-e2e-error.json·db-report-error.txt·storage-expiry-control.json이다. 기존 컨테이너 22개/볼륨 57개와 상태를 보존했고 신규 전용 MinIO 1개만 추가했다(resource-preservation-final.json). 검증용 Catalog 출시 입력과 ZIP은 계약 픽스처이며 실제 Studio/Review 흐름이나 정산 환불 조정의 완료가 아니다. 실제 제공사 TEST 정상 성공 경로 1건을 검증한 것으로, 장애·응답 유실·부분 취소 등은 앞선 OCI 계약 픽스처 검증과 구분한다. 관리자 심사/재시도 HTTP·장기 대기 운영·재구매·정산은 후속이다.


## 2026-10-28 관리자 최초 환불 판정 확정 A안

사용자는 관리자 두 명의 승인·반려가 경합할 때 먼저 저장된 판정을 확정하는 A안을 선택했다. 승인은 이미 이용권 정지 요청을 발행할 수 있으므로, 판정을 뒤집으려면 모듈 간 중단·복구와 PG 송신 경합을 함께 다뤄야 한다. 이번에는 판정 변경을 제공하지 않는다. 잘못 반려한 경우의 재접수·재심사, 잘못 승인한 경우의 별도 구제 절차는 미결이며 새 심사 회차를 자동으로 만들지 않는다.

- `GET /api/v1/admin/refunds`: ADMIN 전용, 기본 `PENDING_REVIEW`, 상태 필터·page/size(기본 0/20, 최대 100), 최초 접수 시각·DB ID 순서. `GET /api/v1/admin/refunds/{refundId}`는 원구매 문맥·현재 처리 상태·최초 판정 기록을 조회한다.
- `POST /api/v1/admin/refunds/{refundId}/decision`: requestId(UUID)·decision(APPROVED/REJECTED)·reason(공백 제외 필수, 최대 500자). 판정자는 본문의 식별자가 아닌 검증된 ADMIN JWT sub다. HTTP 200은 판정 저장이며 PG 취소 완료가 아니다. 읽기와 판정 응답은 no-store다.
- 최초 판정 명령 ID·환불 ID·판정자·판정·사유가 모두 같을 때만 재전송으로 인정한다. 이후 업무가 진행돼도 최초 시각·내용의 영수증을 그대로 반환하고 Outbox를 다시 만들지 않는다. 명령 ID를 바꾼 요청·다른 판정자·사유/판정 변경·다른 환불에서 사용한 명령은 409다. 클라이언트는 응답을 잃었을 때 같은 명령 ID와 내용을 재사용해야 한다.
- 자동 대상이 아닌 PENDING_REVIEW만 새 판정할 수 있다. 승인은 PENDING_LICENSE_GRANT로 전환하고, 원구매와 일치하는 실제 LicenseGranted 근거가 있으면 기존 LicenseSuspensionRequested를 한 번 기록한다. 지급이 늦으면 기존 지급 수신 서비스가 이를 재개한다. 실제 정지 확인 전에는 Payment 환불 실행이 생기지 않는다. 관리자 API는 PG를 직접 호출하지 않는다.
- 반려는 PG 취소 실패인 FAILED와 구분한 REJECTED다. 고객 조회/반복 접수는 REJECTED·rejectionReason을 반환하고 반복 POST는 200이며 새로운 환불·재심사를 만들지 않는다. 반려 시 이용권 정지·PG 실행·실패 복구 이벤트는 없고 completedAt은 비어 있다. 늦은 지급 이벤트도 반려를 뒤집지 않는다. 승인 사유·판정자·명령 ID는 고객 응답에 공개하지 않는다. 관리자에게 반려 사유가 고객에게 표시됨을 API 명세에 알린다.
- 기존 Order 잠금 순서를 유지한다. 잠금 대기 전에 Refund 엔티티를 미리 관리 상태로 읽지 않고 주문 번호 스칼라만 읽어, 잠금을 얻은 후 최신 Refund를 읽는다. 판정·감사 기록·정지 요청 Outbox는 한 트랜잭션이다. 다른 주문의 같은 명령 동시 사용은 DB 유일 키가 막고 패자는 전부 롤백한다.
- Order V20은 기존 행을 고치지 않고 판정 감사 열·명령 유일 키·대기 조회 인덱스·일괄 필드/상태/시각 CHECK를 추가한다. 기존 V19의 자동 완료·심사 대기에 가짜 관리자 이력을 채우지 않는다. 원래 PAID·금액·첫 접수 시각·4시간 자동 판정은 유지한다.

검증 범위는 아래에 기록한다. 실제 Auth의 ADMIN 계정 발급/역할 부여·관리자 UI·실제 수동 심사 PG 환불은 이 정책 선택에 포함해 임의 실행하지 않는다. 기존 별도 9,000원 PAID 주문에는 환불을 접수하거나 승인하지 않는다.


2026-10-28 관리자 최초 판정 A안 검증: 도메인 Red 6건 뒤 구현했다. 로컬 Order 단위 290건·통합 컴파일·bootJar 통과. OCI full에서 새 API 명세 차이와 JDBC CHECK 위반 예외 분류 테스트 1건을 보정하고, 명세 갱신 후 corrected 전체 --rerun-tasks에서 Order 단위 290·통합 114건이 모두 실패/오류/건너뜀 0이다. 새 통합 19건은 실제 JWT 권한·페이지/상세·고객 반려 상태·동시 반대/같은 판정·명령의 다른 환불 재사용·지급 경합·실제 Outbox 삽입 후 롤백·기존 정지/결과 흐름·V19 자료 보존을 검증한다. 업무 로직/V20은 최초 OCI 이후 바꾸지 않았으며 후속 운영 코드 변경은 새 API의 명시적 OpenAPI operationId뿐이다. 기존 3개 경로·5개 연산의 명세는 같고 RefundResponse에 REJECTED/rejectionReason과 관리자 3개 API만 추가했다. 소스 1,100개·실제 비밀값 포함 0·기존 컨테이너 23개/볼륨 57개 및 상태 보존. runs/refund-review-oci-d1jx_po3/collected/results/{full,schema-update,corrected}와 runs/refund-review-tdd-brt1d7x3에 근거가 있다. schema-update의 단위 XML은 직전 full의 결과이며 재실행 수치가 아니다.

검증된 Order runtime에 V20을 적용했다(PID 33244, JAR SHA-256 317c5978346e093e7949e737569ff56fdb08d007f10633f7ac95ebff59b2c2fa). 두 실제 주문의 원 PAID API 사실과 기존 환불 완료·이용권 SUSPENDED/신규 Download 403·PG 전액 취소 1건을 보존했다. 별도 보호 결제는 PG PAID/환불 GET 404다. 실제 MEMBER의 관리자 목록/상세 403을 확인했고 실제 관리자 판정·PG 취소 POST는 추가하지 않았다. 다른 앱/SSH PID·비밀 설정 해시를 보존했다. 근거는 runs/refund-review-tdd-brt1d7x3/live-activation.json이다. 실제 ADMIN 계정·UI를 이용한 수동 심사 PG 환불 전체 검증은 별도 후속이다.


2026-10-28 Gateway 관리자 환불 경로 보완: 서비스 직접 호출과 별도로 /api/v1/admin/refunds의 목록/상세/판정이 Order에 도달하도록 라우트와 ADMIN 허용 경계를 추가했다. 미등록 경로/메서드는 거절하고 Bearer 전달·X-Member-Id 제거를 검증했다. 로컬 Red 4건 뒤 Gateway 단위 97건·통합 컴파일·bootJar가 통과했다. OCI Gateway 단위 97·Redis 통합 1건이 실패/오류/건너뜀 0이고 기존 컨테이너 23개/볼륨 57개를 보존했다. 근거는 runs/refund-review-gateway-oci-7l32q55b/collected/results/full, 최초 Red는 runs/refund-review-tdd-brt1d7x3/gateway-red.json이다. HTTP upstream은 Order 계약 픽스처이며 실제 관리자 로그인/수동 PG 환불 전체 검증을 대신하지 않는다. 기존 실제 주문 검증 스택에는 Gateway 프로세스를 새로 기동하지 않았다.
