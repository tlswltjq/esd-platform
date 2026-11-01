# G3 외부 부담 혜택 A·PortOne 원승인 조회 연결

2026-10-31. 사용자는 [결정 40](decisions.md#verified-external-benefit-allocation)의 **A: 확인된 외부 부담 혜택에 적용**을 선택했다. 현재 TOSSPAY_V2 TEST의 승인 거래를 Settlement가 직접 조회하고, 총액·실결제·외부 혜택을 보존하며 주문 금액으로 배분하는 첫 연결을 구현·검증했다. [책임 분리·비용 독립 월 마감](settlement-separation-verification.md)의 결정 38·39를 유지한다. G3 전체 완료와 실제 PG 비용·지급 자료 연결은 별도다.

## 정책과 확인한 근거

기존 실제 TEST 관측은 주문 총액 9,000원·PG 실결제 8,976원·혜택 24원이다. [토스페이 공식 FAQ](https://docs-pay.toss.im/faq-v2)는 토스 포인트·즉시할인이 토스 부담이며 결제 요청 금액을 기준으로 수수료를 계산한다고 설명한다. [정산 상세 API](https://docs-pay.toss.im/reference/settlement/detail)의 수수료 부과 대상도 실결제·토스 부담·포인트를 합친 금액이다. 이 근거와 검증한 상점·채널·거래를 연결해 외부 부담을 판별한다. 다른 결제사의 혜택이나 플랫폼/판매자 쿠폰에 자동 적용하지 않는다.

현재 모의 플랫폼 요율 10%에서 이 원구매의 판매자 배분은 **8,100원**, 플랫폼 몫은 **900원**이다. 고객의 결제수단 승인액 8,976원은 플랫폼 입금액이 아니며 이를 판매자 배분 기준으로 치환하지 않는다. PG 비용과 실제 수령 자료는 별도로 확인한다. 플랫폼 몫은 PG 비용·다른 비용·세금을 차감한 순이익이 아니다.

토스페이 정산 상세 API는 TEST 결제가 실제 지급되지 않아 TEST 키의 결과가 빈 목록·0원 합계라고 명시한다. 빈 결과를 거래별 PG 비용 0원이나 실제 수령 완료의 증거로 사용하지 않는다. 이번에는 새 실제 PortOne 요청·승인·취소·송금이나 기존 거래의 재조회도 수행하지 않았다. 기존 TEST 관측과 이번 루프백 계약 검증을 구분한다.

## 구현한 경계

- [PortOnePaymentHttpClient](../apps/settlement/src/main/java/com/stove/settlement/adapter/PortOnePaymentHttpClient.java)는 Settlement의 기존 `core.port.PgPayments`를 구현하고 `GET /payments/{paymentId}?storeId=...`만 호출한다. Payment HTTP·DB를 경유하지 않는다.
- ID·상점·V2·PAID·KRW·TEST·설정된 채널 키·TOSSPAY_V2와 `customData.orderNo`, 원승인 거래 ID/시각·총액/실결제/혜택/취소액을 엄격히 검증한다. 숫자 문자열·누락·중복 JSON 키·후행 JSON과 다른 채널/구매를 접수하지 않는다. 나노초 시각은 기존 계약처럼 마이크로초로 정규화한다.
- 새 `PgFunding`이 총액·실결제·혜택·상점/채널·공급자·외부 부담 정책 `TOSSPAY_EXTERNAL_BENEFIT_V1`을 소유한다. 해당 정책 ID는 이 프로젝트의 검증 규칙 버전이다. 실결제 ≥ 0, 총액 = 실결제 + 혜택을 보장하며 `PORTONE_TEST` 거래에는 이 근거가 필수다.
- `PgTransactionRecord`에 이 근거를 불변 저장한다. 기존 매출 접수 트랜잭션의 PG 거래·매출·판매자 월·Inbox와 원자 저장하고, 나중의 근거가 바뀌면 409로 보존한다. 같은 원구매 이벤트는 PG 장애 중에도 최초 결과로 수렴하고 외부 조회를 반복하지 않는다.
- ADMIN 매출·거래 재조회 응답에 `funding`을 추가했다. CREATOR 응답에는 PG 구성·상점/채널을 노출하지 않는다. 기존 9개 경로/10개 연산과 Gateway ADMIN 경로는 유지하고 명세 필드만 갱신했다.
- 원승인 조회는 PG 비용을 만들지 않는다. `pgCostStatus=UNCONFIRMED`, 비용/플랫폼 잔여액 NULL을 유지하면서 판매자 월 마감은 가능하다. PortOne 프로필의 신규 지급 명세 접수는 [UnavailablePgSettlements](../apps/settlement/src/main/java/com/stove/settlement/adapter/UnavailablePgSettlements.java)가 503으로 명시적으로 미연동을 반환한다. 모의 명세나 빈 0원 수령을 만들지 않는다. 기존 저장 명세의 조회·재대사는 저장 사실을 사용한다.

부담 근거를 확인할 수 없는 응답·미지원 채널은 배분/Inbox를 쓰지 않고 상류 미확정으로 남긴다. Kafka 접수는 기존 재시도·소진 후 원본 DLT 경로를 사용하며 운영자가 원인을 확인한 뒤 원래 이벤트를 재전달한다. 새 일반 프로모션 엔진·거래별 수동 판정 화면은 추가하지 않았다. 이미 검증된 판매자 금액의 마감을 비용 때문에 막는 정책으로 되돌리지 않는다.

첫 연결의 접수 범위는 PAID·취소액 0인 원승인이다. 이미 취소/부분 취소된 원매출의 지연 접수와 환불 조정은 후속이며 이 상태를 임의 PAID로 정규화하지 않는다. 검증한 뒤 같은 거래가 취소되어도 저장한 원매출·배분을 덮어쓰지 않는다. 실제 환불 반영이 끝났다는 의미가 아니다.

## 설정과 이행

`stove.settlement.pg-provider` / `SETTLEMENT_PG_PROVIDER`는 기본 `SIMULATED`이며 명시적으로 `PORTONE_TEST`를 선택하면 전용 config가 어댑터를 조립한다. PortOne의 API URL·Secret·storeId·channelKey·연결/읽기 제한은 `PortOneSettlementProperties`에서 바인딩하고 어댑터에는 값·클라이언트만 주입한다. Mock PG 키를 대신 사용하거나 자동 fallback하지 않는다. 미지원 공급자·불완전 설정은 시작을 거절하고 설정 문자열은 Secret을 숨긴다. 실제 URL은 HTTPS `api.portone.io`, 계약 픽스처는 루프백만 허용한다. [실행 설정](settlement-guide.md#portone-test-원승인-조회)을 따른다.

V9는 기존 PG 거래에 nullable 지급 구성 열과 전부 없음/전부 검증된 구성의 DB 제약을 추가한다. 과거 행에 실결제·혜택·외부 부담을 소급 생성하지 않는다. V1–V8 SQL 파일은 변경하지 않았다. 실제 MySQL에서 V7의 매출/마감/불일치 명세를 V8→V9로 이행해 거래·확정 비용·원자료·체크섬을 보존했고, 과거 새 열의 NULL과 부분 구성 저장 거절을 확인했다. 공유 DB에는 적용하지 않았다.

## TDD와 OCI 결과

로컬에서 편집하고 `ssh sng`의 `/tmp/esd-g3-portone-20261031.uPXeH2Dl/work`에서 실행했다.

1. 외부 혜택 배분·잘못된 구성·공급자 조회 실패 계약을 먼저 추가해 새 모델/어댑터 부재의 컴파일 Red를 확인했다. 기존 운영 장애를 재현한 Red는 아니다.
2. 단위·아키텍처·통합 컴파일을 통과했다. 새로운 PortOne 업무 통합은 실제 HTTP 어댑터와 Catalog HTTP 어댑터를 루프백 서버에 연결하고 실제 MySQL에 저장한다. 공급자 인증 헤더·상점 쿼리와 GET 전용 호출을 확인한다. 실제 제공사 Secret은 사용하지 않았다.
3. PortOne 통합 4건에서 9,000/8,976/24 보존·8,100 배분·비용 미확정 월 마감, 구매/채널/금액/상태/JSON 거절, 변경된 지급 구성의 409·불변, 공급자 장애·실제 Inbox 저장 후 장애의 전체 롤백과 미연동 명세 503을 확인했다. signed JWT의 ADMIN 조회와 CREATOR 정보 제한도 포함한다.
4. 집중 실행에서 업무 4건은 통과했으며 MySQL 부분 구성 거절의 예외 분류를 테스트가 잘못 예상했다. 실제 SQLState HY000·오류 3819의 `UncategorizedSQLException`과 제약 이름을 확인하도록 기대값을 보정했다. 제약 자체는 정상 동작했으며 운영 코드를 바꾼 수정이 아니다. 이후 이행·명세를 통과하고 최종 명세 갱신 플래그 없이 전체 실행했다.

```sh
CI=true ./gradlew :apps:settlement:check :apps:settlement:bootJar --console=plain
```

| 최종 검사 | 사례 | 결과 |
|---|---:|---|
| 단위·아키텍처 | 55 (아키텍처 39 포함) | 실패·오류·건너뜀 0 |
| PortOne HTTP·DB 업무 | 4 | 실패·오류·건너뜀 0 |
| 기존 업무/HTTP·Kafka | 23 | 실패·오류·건너뜀 0 |
| 과거 DB 이행 | 2 | 실패·오류·건너뜀 0 |
| 합계 | **84** | check·bootJar 통과, 1분 11초 |

이번 최종 XML이 이전 구조 분리의 관련 검사를 포함한 최신 결과다. 원자료는 `runs/g3-portone-20261031/collected/`의 단계별 로그·최종 XML·소스 해시·요약·자원 스냅샷이다. 정산/공통 소스·Gradle 입력 **292개**가 원격 작업본과 같았으며 최종 JAR SHA-256은 `de75ec00a59845627ec5d3559590b5a4de1874bd77d59e1e06822c6348e2b09b`다. 검증 뒤에는 문서만 갱신했다.

Testcontainers 종료 후 기존 공유 컨테이너 **23개**의 ID·실행/건강 상태·시작 시각·재시작 수·마운트와 볼륨 **57개**의 동일성을 대조했다. 별도 서비스 프로세스를 띄우지 않았고 추가 자원은 남지 않았다. 기존 실제 런타임·공유 자료·거래는 보존했다. 전체 Auth/Gateway·실제 PortOne을 포함한 사용자 여정, 실제 비용·입금·판매자 송금 검증은 수행하지 않았다.

## 다음 단위

현재 지원하는 외부 부담 혜택 A와 월 마감 정책은 다시 묻지 않는다. 다음 업무 논의는 **전액 환불 시 원매출의 판매자 몫과 플랫폼 수수료를 어떻게 조정할지**다. 원매출 보존·취소 후 지연 접수·마감 전후 조정 월과 연결한다. PG 비용 반환·지급 자료는 직접 근거가 필요한 별도 사실이며 현재 TEST의 빈 결과로 확정하지 않는다. P4 재구매·일반 할인 부담 정책·운영 API 전체 구조는 후속으로 유지한다.
