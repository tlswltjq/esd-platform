# G3 토스페이 정산 API 수집·원본·대사

2026-11-02. 사용자는 [결정 43](decisions.md#tosspay-statement-api-source)의 **A: 토스페이 공식 정산 API 자동 수집**을 선택했다. [출처 조사와 선택 배경](settlement-pg-evidence-plan.md)을 기준으로 같은 Settlement 모듈에 읽기 수집·불변 원본/거래 근거·대사·관리자 조회·주기 재수집을 구현하고 OCI **144건**·check·bootJar를 통과했다. 실제 가맹점 자료 검증과 현재 TEST의 비용 미확정은 구분한다.

## 데이터 흐름

`ReceivePgStatement` → `core.port.PgStatementSource` → `TossPayStatementHttpClient`가 [공식 정산 상세 조회](https://docs-pay.toss.im/reference/settlement/detail)를 호출한다. 이 공급자는 읽기 조회에도 POST를 사용하며 승인/취소 명령은 없다. 조회는 업무 DB 트랜잭션 밖에서 수행한다. 공급자 가맹점 API Key는 요청 본문에만 사용하고 응답 원본·설정 문자열·실패 로그에 보관하지 않는다.

수집은 지급일 DUE 또는 거래일 SETTLE과 기준일을 받는다. 모든 커서 페이지를 읽고 페이지별 전체 합계의 일관성·마지막 커서·거래 ID 중복·DUE 지급일·금액/수수료/VAT 부호와 최종 합계를 검증한 뒤 저장한다. 숫자 문자열·누락/비정상 값·중복 JSON 키/후행 JSON·중간 HTTP 실패·커서 순환·페이지 제한 초과는 완성된 명세로 접수하지 않는다. 알 수 없는 부가 필드는 원본에 보존한다.

승인은 양수 거래액과 음수 수수료/VAT, 환불은 음수 거래액과 양수 반환 수수료/VAT를 보존한다. 환불 반환 0원이 명시된 행은 그 관측값을 보존하지만 미수집/빈 TEST 결과로 반환 0원을 생성하지 않는다. `reportedNetAmount`는 보고된 거래액+수수료+수수료 VAT의 현금 합계이며 회계상 순이익·은행 잔액이 아니다.

## 원본과 중복

V11은 기존 테이블을 변경하지 않고 네 테이블을 추가한다.

| 테이블 | 소유하는 사실 |
|---|---|
| settlement_pg_statement | 가맹점/TEST·LIVE·상점/채널·검증된 매핑 여부, 조회 기준/일자, 원본 fingerprint·합계·최초 수집자/시각과 대사 상태 |
| settlement_pg_statement_page | 요청/다음 커서·순번·응답 원문 |
| settlement_pg_statement_entry | 가맹점+환경+거래 ID별 불변 거래·금액·수수료·VAT·지급일 |
| settlement_pg_statement_item | 수집본과 정본 거래의 연결·순번, 해당 수집 범위의 대사 결과/결제 연결/확인 시각 |

헤더·페이지·신규 거래·연결 항목은 같은 트랜잭션에서 저장한다. 같은 조회/범위/원본은 fingerprint로 최초 수집자·시각을 유지하고 동시 수집도 하나로 수렴한다. DUE·SETTLE 또는 서로 다른 관측에서 같은 거래가 다시 보여도 정본 거래는 하나다. 동일 거래 ID의 내용이 바뀌면 자동 덮어쓰기 없이 충돌로 거절한다. 수집본 목록은 여러 조회 관측이므로 그 합계를 모두 더해 비용 원장으로 사용하지 않는다.

대사는 저장 뒤 별도 트랜잭션에서 진행한다. 대사 실패 시 이미 수집한 원본은 보존하고 다음 동일 수집 또는 명시적 재대사로 다시 확인할 수 있다. 대사 과정은 원래 매출·환불·판매자 월·모의 비용/명세에 쓰지 않는다.

## 거래 연결과 상태

거래별 대사는 내부 원매출/승인·상점/채널·금액·환불과 수집된 PG 거래를 대조한다. 구매·환불은 일괄 조회하고 환불 관측 합계는 가맹점/환경 내 중복 없는 거래를 사용한다. 내부 환불에 비해 관측 환불 합계가 작으면 대기, 크면 불일치다. 부분 관측으로 전액 환불 대사를 완료하지 않는다.

| 상태/코드 | 뜻 |
|---|---|
| EMPTY_TEST | TEST의 빈 조회 관측. 어떤 구매의 비용 0원이나 지급 완료도 확정하지 않음 |
| EMPTY | 해당 LIVE 조회의 빈 관측. 개별 비용/은행 입금의 확정과 구분 |
| RECEIVED·UNCHECKED | 원본 접수 뒤 대사 전 |
| PENDING | 원매출/환불·환경·거래 키 검증·관측 합계 등의 근거 대기 |
| MISSING_SALE·MISSING_REFUND | 내부 대응 사실 미도착 |
| SOURCE_ENVIRONMENT_PENDING | LIVE 명세와 연결할 LIVE 승인 근거가 없음. 현재 PORTONE_TEST를 실지급 거래로 승격하지 않음 |
| IDENTITY_UNVERIFIED | 상점별 거래 키 대응을 아직 검증하지 않음 |
| REFUND_AMOUNT_PENDING | 확인된 내부 전액 환불보다 관측된 PG 환불 합계가 작음 |
| MISMATCH | DIFFERENT_MERCHANT/ORDER/TRANSACTION/AMOUNT·EXCESS_REFUND·AMBIGUOUS_PAYMENT 등 |
| MATCHED_REPORTED | 검증된 범위에서 PG 보고와 내부 사실이 일치함. 은행 실입금·판매자 송금 완료가 아님 |

**실제 키 대응은 아직 미검증이다.** 선택 가능한 구현의 대응은 `PortOne pgTxId ↔ 토스페이 payToken`, `PortOne paymentId ↔ PG orderNo`다. 내부 업무 orderNo를 PG orderNo와 같다고 간주하지 않는다. `identity-mapping-verified`는 실제 가맹점/상점/채널 자료로 이 두 대응을 검증한 경우에만 활성화한다. 기본값은 false이고 각 수집본에 당시 설정을 보존한다. 이 설정만으로 TEST 원승인을 LIVE로 바꾸거나 새 승인을 만들 수 없다.

**현재 승인 경로는 PORTONE_TEST이고 LIVE 승인 접수는 이번에 추가하지 않았다.** 따라서 현재 실제 구매에서 LIVE 명세의 MATCHED_REPORTED나 비용 확정을 주장하지 않는다. 대사 일치/불일치 계산의 LIVE 입력 검사는 계약 단위 픽스처이며 실제 LIVE 승인 수신 경로의 검증이 아니다. 월 ADMIN의 기존 PG 비용/잔여액은 이 수집본을 보고 임의 확정하지 않는다. PG가 보고한 기본 수수료/VAT·반환/현금 합계는 새 명세/항목 조회에서 확인한다. 매출 월/환불 조정 월과 PG 비용의 기간 배분·기존 월 금융 요약 통합, 일반 API 밖의 카드 차액 정산은 후속 범위다.

## 운영 조회와 재수집

| 경로 | 동작 |
|---|---|
| POST `/api/v1/settlements/pg-statements` | ADMIN이 dateType·baseDate로 공급자 전체 페이지 수집을 요청. 금액·수령 상태를 입력하지 않음 |
| GET `/api/v1/settlements/pg-statements` | ADMIN 수집본 페이지 목록 |
| GET `/api/v1/settlements/pg-statements/{id}` | 범위·조회·fingerprint·합계·최초 수집·대사 요약 |
| GET `/api/v1/settlements/pg-statements/{id}/items` | 거래별 보고 금액/수수료/VAT와 대사 코드·결제 연결의 페이지 목록 |
| GET `/api/v1/settlements/pg-statements/{id}/pages/{position}` | 해당 순번의 커서·응답 원문 |
| POST `/api/v1/settlements/pg-statements/{id}/reconcile` | 저장한 원본과 현재 내부 사실의 재대사. PG 재호출 없음 |

Gateway의 기존 ADMIN 정산 범위가 이 API를 포함하며 Gateway 소스는 변경하지 않았다. CREATOR/MEMBER와 익명은 이 원본/금융 조회에 접근할 수 없다. 기존 판매자 조회·월 마감·환불 정책을 유지한다. 공개 명세는 17개 경로·19개 연산으로 늘어난다.

자동 수집은 config가 타이머/주기/값을 조립하고 `SynchronizePgStatements`를 호출한다. 설정 시 KST 10시부터 기본 1시간 간격으로 오늘 DUE 및 최근 7일 DUE/SETTLE을 재조회한다. 실패한 날짜가 있어도 다른 날짜를 계속 처리하며 다음 주기에 다시 확인한다. 기본 lookback은 1–31일로 제한하고 더 오래된 날짜는 API로 지정할 수 있다. 공급자 최대 조회 범위 1년·당일 SETTLE 미적재를 반영한다. 미래/범위 밖 조회는 거절하고 오늘 DUE도 10시 전에는 받지 않는다. 시각/주기는 운영 기본값이며 판매자 마감·금융 확정 정책이 아니다.

주기 설정은 `enabled`와 `scheduled-enabled`가 모두 true일 때 적용한다. 가맹점 키가 없는 기본 상태는 수집 미연동 503이며 빈 보고서를 만들지 않는다. 실제 스택에는 이 설정을 적용하지 않았다.

## 검증 기록

로컬에서 편집하고 `ssh sng`의 `/tmp/esd-g3-portone-20261031.uPXeH2Dl/tosspay-evidence-20261102/work`에서 실행했다. 앞선 환불 원장 검증본의 321개 해시를 대조하고 서버 안에서 격리 사본을 만들었다. 신규 도메인 테스트를 먼저 전송해 타입 부재의 컴파일 Red를 확인했다. 이번 소스 전송은 Settlement의 변경 42개(신규 34개 포함)와 해시 명세에 한정했다.

첫 구현의 아키텍처 검사에서 config→인바운드 스케줄러 의존과 서비스/중첩 타입 명칭 위반을 발견했다. 수집 서비스를 Receive로 정리하고 타이머는 config, 반복 업무는 core로 이동했으며 거래 연결 키는 domain 값으로 옮겼다. 아키텍처 규칙 자체는 완화하지 않았다. 초기 롤백 테스트의 MySQL 트리거 생성은 검증 계정에 SUPER가 없어 실패했으므로, 서버 권한/설정을 바꾸지 않고 검증 전용 테이블의 임시 CHECK로 실제 쓰기 실패를 주입하도록 바꿨다.

아키텍처·단위와 통합 컴파일 뒤 새 HTTP/DB·이행·TEST 격리/기본 미연동 사례를 집중 실행했다. 별도 단위 검사로 실제 설정 조건의 조회기/타이머 조립과 기본 비활성을 확인했다. 의도한 명세 변경은 갱신 플래그로 한 번 생성·회수하고, 최종 게이트는 선택 필터나 명세 갱신 플래그 없이 실행했다.

```sh
CI=true ./gradlew :apps:settlement:check :apps:settlement:bootJar --console=plain
```

| 최종 검사 | 사례 | 결과 |
|---|---:|---|
| 단위·아키텍처 | 92 (아키텍처 39 포함) | 실패·오류·건너뜀 0 |
| 업무/HTTP·MySQL/Kafka 통합 | 50 | 실패·오류·건너뜀 0 |
| DB 이행 | 2 | 실패·오류·건너뜀 0 |
| 합계 | **144** | check·bootJar 통과, 1분 33초 |

신규 단위 15개는 승인/환불의 수수료·VAT 부호/합계와 명시적인 반환 0원, 대사 입력의 LIVE/TEST/상점/키/금액/전체 환불 합계, 실제 루프백 HTTP의 전체 커서·원문·엄격한 JSON·오류/중복/순환/전체 합계, 설정의 목적지·키 환경·문자열 비노출, KST 적재 시각과 실패 뒤 다른 날짜 진행 및 타이머/미연동 조립을 검증했다.

신규 통합 9개는 실제 HTTP→MySQL 원본/거래 저장과 ADMIN/CREATOR/익명 경계, 페이지 조회, 동일/다른 날짜 조회의 거래 공유, 동시 수집, 동일 거래 내용 충돌 보존, 중간 HTTP 실패와 임시 CHECK가 발생시킨 실제 item INSERT 실패의 전체 롤백, 원매출 지연 접수 뒤 TEST/LIVE 대기·월 보존, 빈 TEST의 비용 미확정, 기본 미연동 503을 다룬다. 기존 이행 2개를 확장해 V1–V10 체크섬·매출/PG 거래/비용·마감·명세 보존과 신규 테이블의 빈 시작을 확인했다. 앞선 환불/월 마감·DLT/원본 재처리 검사도 최신 144건에 포함된다.

원자료는 로컬 `runs/g3-tosspay-evidence-20261102/collected/`의 Red/아키텍처 실패·집중/수정·타이머·명세 갱신·최종 로그, 최종 XML, 소스/전송 명세, 전후 자원 스냅샷과 `verification-summary.json`이다. **355개 소스/Gradle 입력 해시**가 로컬·OCI에서 일치했다. 최종 JAR SHA-256은 `b2404b24d8f195085862c653ba37c8a41d9bc0b010879673f6bb3d73417d6bc9`이다. 최종 검증 뒤에는 문서만 수정했다.

Testcontainers 종료 뒤 공유 컨테이너 **23개**의 ID·상태/건강·시작 시각·재시작 수·마운트와 볼륨 **57개**가 전후 동일했다. 이전 환불 원장 검증본도 **321개 해시**가 그대로였다. 새 실제 PortOne/토스페이 조회·승인/취소·지급/송금, 실제 계정 설정과 기존 런타임 적용은 0건이다. 은행 실입금·세무 판단·음수 잔액 자동 이월/회수·P4 재구매와 G3 전체 완료는 이번 단위와 구분한다.
