# License 실행·라이브러리·재전달 확인

[문서 목록](README.md) · [MVP 범위·검증 기록](license-mvp-plan.md) · [모델](domain/models/fulfillment.md#license)

통합 구현·OCI 검증은 [완료 기록](license-mvp-plan.md#통합-수정-완료-기록)을 따른다. 본인용 동일 상품은 한 번만 구매한다. Order의 구매 제한과 LicensePurchase 통합 범위는 [통합 계획](license-mvp-plan.md#통합-및-중복-구매-제한-수정-계획)을 따른다.

Order가 최초 PAID를 반영하면 자기 주문 스냅샷의 `OrderPaid`를 같은 트랜잭션의 Outbox에 저장한다.
License는 이 이벤트의 출처 전체와 상품 보유권을 product_license 한 행에 저장한다. 추가 결제 승인·고객의 구매 확정은 없다.

## 코드와 처리 경계

| 유스케이스 | 책임 |
|---|---|
| GrantLicense | 전체 출처·상품 충돌·Inbox 비교, 출처를 포함한 권한·Inbox의 원자적 지급 |
| SearchLicenses | JWT sub 기준 목록·페이지 조회 |
| ReadLicense | 본인·상품 코드별 상세, 타인·없는 상품 404 |

OrderEventListener → LicenseOrderMapper.from(...) → PaidOrder → GrantLicense 순으로 입력한다.
Order는 같은 구매자·상품의 PENDING·PAID 주문이 있으면 새 요청을 409로 거절한다. 최종 FAILED 이후에는 새 requestId로 다시 주문할 수 있다.
License는 같은 원본 이벤트만 정상 재전달로 처리하고 다른 주문의 같은 상품은 충돌로 남긴다.
최초 licenseId·제목·획득 시각은 유지한다. 라이브러리는 Catalog가 중단돼도 저장된 정보로 조회한다.

## 내 라이브러리

MEMBER access token을 사용한다. 인증은 [Auth·Gateway 안내](auth-bff.md)를 따른다.

```sh
curl -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/v1/library?page=0&size=20"
curl -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/v1/library/$PRODUCT_CODE"
```

목록은 acquiredAt·내부 id 역순이며 page는 0 이상, size는 1~100이다.
응답 항목은 licenseId·productId·productCode·title·acquiredAt이다. licenseId는 공개 UUID이며 비밀 키가 아니다.
익명 401, MEMBER 없음 403, 미보유·타인 404, 잘못된 페이지·코드 형식 400이다.
JWT sub를 사용하며 memberId·buyerSubject 쿼리와 X-Member-Id는 주체를 바꾸지 않는다.

Order PAID 직후에는 이벤트 전달 중이므로 라이브러리에 없거나 상세가 404일 수 있다.
조회는 지급을 수행하지 않는다. 환불·회수·기간제·다운로드 URL 제공은 후속 범위다.

## OCI 검증

로컬은 편집·동기화만 수행한다.

```sh
./tools/remote/remote.sh gradle :apps:license:test :apps:license:integrationTest :apps:order:integrationTest :apps:payment:test -Pstove.infraTest.maxParallel=1 --no-daemon
./tools/remote/remote.sh sync
```

기존 OCI의 원격 저장소 디렉터리에서 다음을 실행한다.

```sh
python3 tools/verification/verify-license.py
```

스크립트는 완료된 여덟 앱과 License·독립 MySQL·Kafka·Redis·MinIO를 실행한다.
기본 앱 포트 오프셋은 31000이며 LICENSE_TEST_PORT_OFFSET으로 바꾼다. 기존 공유 DB·볼륨을 사용하지 않는다.
완료 후 해당 실행의 프로세스·컨테이너·볼륨만 정리한다. 로그·license.json·summary.json·단계별 JUnit XML은 출력한 임시 경로에 남는다.

13단계로 실제 로그인·심사·출시·주문·결제·라이브러리, PENDING·거절 무지급, 중복 구매 차단·실패 후 재주문·소유자 분리,
License 중단 후 접수 재개, 커밋 직후 ACK 전 프로세스 종료·원본 재전달, Order 릴레이·브로커·Catalog 중단과 원래 Outbox 복구를 확인한다.
장애 주입은 integrationTest 클래스패스로만 실행하며 운영 bootJar·업무 HTTP API에 포함하지 않는다.

## 기존 자료와 복구 경계

Flyway V1~V4와 구 자료를 유지한다. V5는 1:1 구매 근거를 product_license로 복사하고 출처 제약을 추가한다.
기존 license_purchase는 과거 자료로 보존하며 런타임에서 읽거나 쓰지 않는다. 신규 지급은 License 한 행·Inbox 한 행이다.
구 License Outbox 릴레이는 꺼 두며 LicenseIssued·LicenseIssueFailed·LicenseRevoked를 발행하지 않는다.
새 소비 그룹은 license-paid-order다. 구 그룹과 구 Inbox를 새 구매 근거로 변환하지 않는다.

OrderPaid 발행 도입 전에 이미 PAID였던 주문은 재전달·조회·기동으로 자동 소급 지급하지 않는다.
공유 환경에 적용하기 전에는 과거 PAID·미발행 Outbox·Kafka 오프셋과 소급 지급 필요성을 별도로 대조해야 한다.
이 독립 검증은 공유 환경의 자료 전환 완료를 뜻하지 않는다.

License DB 유실 뒤 전량 재구축은 이번 범위가 아니다. 구 DB 유실 런북을 새 테이블에 그대로 적용하지 않는다.
오류 재투입은 원래 eventId·키·헤더·본문을 유지하고 원인을 해결한 뒤 수행한다.
지급 오류는 Payment FAILED나 자동 환불을 의미하지 않는다.

전환 전에는 쓰기를 중지한 대상 DB에 [읽기 전용 점검 SQL](../tools/migrations/license/license-merge-preflight.sql)을 실행한다.
Order PENDING·PAID 중복, License 복수/누락/불일치 출처·Inbox가 있으면 자료 전환을 중단한다.
이 SQL은 Order V10·License V4 자료용이다. 통합 후 새 지급 자료를 과거 license_purchase와 대조하는 도구가 아니다.
미발행 OrderPaid Outbox와 Kafka 미소비·DLT·재투입 자료도 함께 확인한다.
V5는 검사에 실패하면 출처 컬럼을 추가하기 전에 거부한다. DDL 중간 실패의 자동 롤백을 가정하지 말고 실제 스키마·Flyway 실패 기록을 확인한 뒤 백업 복구 또는 검증한 전진 복구를 수행한다.
