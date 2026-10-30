# G1 PortOne TEST 실제 결제 생명주기 검증

2026-10-29 실행, 2026-10-30 결과 수집·환경 정리 완료. 사용자가 실제 TEST 여정의 실행을 요청했고, PG 할인 처리의 A안을 추가 선택했다. 이 기록은 G1의 이번 실제 결제 검증 범위이며 전 기능 일반화·G1 전체 종료·운영 배포를 뜻하지 않는다.

**2026-10-30 후속:** 초기 진단 건은 보존된 최종 기록의 Order EXPIRED·승인 접수/송신 준비 없음·confirm POST 0회와 현재 PG READY를 대조했다. 승인 전 검증 보류에 만료를 반영하지 못한 경로를 기존 정책 안에서 보완했다. [원인·수정·후속 검증](g1-unadmitted-expiry-fix.md)을 따른다. 아래 실제 TEST 당시의 보류 기록과 597건은 당시 근거로 보존하며, 종료된 원래 스택·DB를 갱신한 결과와 구분한다.

## 검증한 여정

| 사례 | 실제 확인한 결과 |
|---|---|
| 서버 승인·승인 응답 유실 | 승인 POST가 PortOne에서 성공한 뒤 검증 프록시가 응답을 한 번 버렸다. 원래 승인 근거의 GET 재조회로 Payment·Order PAID, 승인 SUCCEEDED, License 지급·본인 라이브러리 반영을 확인했다. confirm POST는 1회다. |
| 개별 실패 뒤 같은 주문 재결제 | 사용자가 TEST 결제창을 닫은 뒤 PortOne FAILED를 서버에서 조회하고 실패 이력 1건을 저장했다. Order·Payment는 PENDING, checkout은 같은 주문·paymentId·9,000원으로 HTTP 200이었다. 같은 주문의 두 번째 시도에서 PAID·라이브러리 지급을 확인했고, 첫 실패 이력의 ID도 유지됐다. 공급자 이력에는 FAILED 1건·PAID 1건, 성공 승인 POST는 1회다. |
| 이탈·실제 30분 만료 | 생성 2026-10-29 23:27:08.565400 KST, 고정 기한 23:57:08.565400. 23:57:09.841946에 Order·Payment EXPIRED, 기존 checkout HTTP 409와 같은 상품의 새 PENDING 주문을 확인했다. 서버·업무 시각 및 DB 생성/승인 시각을 변경하지 않았다. |
| PG 할인 거래 | 실제 승인된 두 거래 각각 총액 9,000원, PG 할인 24원, 고객 실결제 8,976원이었다. 주문 금액은 유지하고 별도 PG 금액 스냅샷·고객 조회를 확인했다. PG 비용은 NULL이다. |
| 실제 웹훅 | 두 PAID 거래의 제공사 조회에서 전용 HTTPS 웹훅 전달이 SUCCEEDED였다. 금융 완료 판단은 제공사 재조회·구매 대조·업무 저장을 기준으로 했다. |

검증 계정은 실제 Auth authorization_code + PKCE로 MEMBER 권한을 받았다. Catalog 상품 공개 이벤트는 검증 픽스처로 발행하고, 실제 Catalog·Order·Payment·License 서비스를 OCI에서 연결했다. Gateway BFF·전체 Studio/Review·Download·Settlement·환불 전체 여정은 이번 실제 여정에 포함하지 않았다.

- 응답 유실 거래: 주문 `50be5c00-3cf9-4016-8afb-9aa8f7eb1312`, 결제 `728a24b9-a4ea-4b40-b3a8-d0955fac4e36`.
- 실패 후 재결제: 주문 `4c65f7ae-6dd7-4274-9c48-35a2fb4f0eed`, 결제 `1fda5f0f-9f6c-4a05-8b96-d7c4e15eedbf`.
- 자연 만료: 주문 `d9e86800-5be1-4abf-bf5c-5b56b4d1cb76`, 결제 `9f6528f3-5e14-438c-8ab0-4b3b6d03d7a4`.

## 실제 응답으로 보완한 계약

첫 TEST 인증에서 PortOne의 READY 응답에도 `amount.paid=9000`이 들어오는 사실을 확인했다. 기존 0원 가정은 이를 잘못된 근거로 분류했다. 실제 FAILED 응답과 거래 이력에서도 같은 필드가 9,000원임을 확인했다. 승인 전·실패의 금액 필드만으로 성공을 추정하지 않고, 공급자 상태와 구매 대조를 유지하도록 수정했다.

이후 실제 승인 결과의 할인 24원 때문에 기존 무할인 조건에 걸렸다. 사용자는 [결정 34](decisions.md#provider-discount-payment-amounts)의 A안을 선택했다.

- PAID의 상점·TEST 채널·통화·주문·원래 승인 시도·주문 총액과 미취소 사실을 대조한다. `실결제액 + PG 할인액 = 주문 총액`을 요구한다.
- `PgPaymentAmounts`를 확정 결과에 포함하고, Payment의 `pg_paid_amount`·`pg_discount_amount`와 공개 조회의 `pgPaidAmount`·`pgDiscountAmount`에 보존한다. 원래 `amount`·주문 할인·OrderPaid 금액은 바꾸지 않는다.
- V32는 금액 쌍의 NULL 여부·PAID 상태·음수 금지·합계 일치를 DB에서도 검사한다. 과거 미확인 값은 NULL로 두고 0원으로 추정하지 않는다. 확정 금액을 바꾼 중복 결과는 거절한다.
- 이미 PAID인 거래는 같은 승인 요청을 다시 송신하지 않고 조회·기존 승인 영수증으로 복구했다. 할인액을 PG 비용이나 판매자 부담으로 분류하지 않았다.

첫 READY 필드 오류를 드러낸 진단용 주문 `0c33eb85-37f4-4038-87e8-9a884484abd8`은 승인 접수 근거 없이 REVIEW_REQUIRED에 멈춘 기록을 보존했다. 이 기록의 Payment PENDING·PG_INVALID_EVIDENCE를 임의 DB 수정이나 새 승인 송신으로 종결하지 않았다. 일반 만료 사례와 구분하며, 금융 보류 건의 운영 종결은 별도 정책·작업으로 남는다.

## TDD·최종 검증

- READY/FAILED의 실제 금액 필드 재현: 관련 테스트에서 2건 Red를 확인한 뒤 Green. 수정된 첫 전체 Payment 검증은 591건이었다.
- 사용자 선택 뒤 할인 PAID 응답 재현: 1건 Red를 확인했다. 금액 스냅샷의 불변성·합계·음수/경계, 응답 유실 뒤 실제 MySQL 저장·공개 조회·중복 방지·DB CHECK를 보강했다.
- 집중 통합 검증에서 JDBC CHECK 예외의 분류 가정과 OpenAPI 파일의 수동 들여쓰기 2건을 바로잡았다. MySQL 오류 3819와 해당 제약명을 확인하고, 실제 명세와 두 공개 스냅샷을 비교했다.

최종 명령은 OpenAPI 갱신 옵션 없이 OCI에서 실행했다.

```sh
./gradlew --offline --max-workers=2 -Pstove.infraTest.maxParallel=1 \
  :apps:payment:check :apps:payment:bootJar --console=plain
```

| 최종 태스크 | 건수 | 실패·오류·건너뜀 |
|---|---:|---:|
| test | 355 | 0 |
| integrationTest | 234 | 0 |
| migrationTest | 8 | 0 |
| 합계 | 597 | 0 |

실행 시간은 386.08초다. 최종 XML을 한 번씩 합산하며 집중 실행·이전 591건을 중복 합산하지 않는다. 변경하지 않은 Order·Gateway·License 등의 전체 테스트를 새로 실행한 것으로 주장하지 않는다. 최초 준비에서 Auth·Catalog·Order·Payment·License의 bootJar가 성공했다. 최종 로컬 변경 파일과 OCI 테스트 소스의 SHA-256이 일치하고, 실제 복구에 사용한 Payment JAR가 최종 빌드 JAR와 일치함을 확인했다.

## 실행 환경·정리·근거

- 원격 격리 경로: `sng:/tmp/esd-g1-live-20261029.295YgSv6`.
- 로컬 원자료: `runs/g1-live-ykqs9jdy/collected/`. 최종 XML·Red/Green 로그·금융/실패/만료 결과·소스 대조·자원 전후 기록을 보관한다. API Secret·paymentToken·비밀 설정은 결과 묶음에서 제외했다.
- TEST 채널 `esd`의 수동 승인을 켜고 저장·재열람으로 확인했다. 검증 종료 때 이 설정을 되돌리지 않았다. 이후 사용량 한도로 브라우저 자동 승인 심사가 거절해 최신 UI 재관찰·완료 화면의 새 캡처는 하지 못했다.
- 기존 공개 웹훅을 유지한 채 이번 검증의 정확한 경로 하나만 추가했다. 종료 뒤 추가 경로를 제거하고 nginx 검증·reload를 수행했으며 원래 설정과 일치한다.
- 이번 앱 5개·검증 서버 2개·전용 DB/Kafka 컨테이너 2개를 종료·정리했다. Kafka의 자동 생성 설정 볼륨 2개는 내용을 보관한 뒤 해당 볼륨만 제거했다. 공유 컨테이너 23개·볼륨 57개 및 ID·시작 시각·재시작 횟수·실행/건강 상태가 검증 전과 같다. bind mount의 업무 DB·Kafka 자료, 소스와 원자료는 보존했다.
- OCI 검증 화면 서비스와 로컬 SSH 전달 프로세스도 종료했다. 최초 sandbox의 프로세스 조회 제한 후 추가 허용으로 검증 PID의 명령·소유권을 확인하고, 전용 SSH 하나를 종료했다. 최종 조회에서 두 검증 PID가 남아 있지 않음을 확인했다. 상시 운영 배포로 취급하거나 이전 PAID 거래의 승인·환불 스크립트를 재실행하지 않는다.

## 다음 범위

이번 실제 TEST 결제·실패 재시도·응답 유실 복구·자연 만료 검증은 완료했다. 금융 보류의 다음 행동·운영 종결까지 포함한 G1 종료 조건을 대조한 뒤 G2의 최신 Gateway·구매/환불·보유권·다운로드 전체 여정으로 진행한다. PG 할인 거래의 환불액·할인 복원·할인 부담 주체·정산 조정은 이번 합의로 확정하거나 구현하지 않았다.

## 후속: G2 정상 구매·다운로드

2026-10-30 [Gateway BFF·실제 TEST 구매·라이브러리·ZIP 다운로드](g2-purchase-download-verification.md)를 추가 검증했다. 이 문서에서 제외했던 Gateway·Download를 일곱 앱의 최신 격리 스택으로 연결한 별도 여정이다. 최신 환불·재구매·Settlement는 계속 구분한다.
