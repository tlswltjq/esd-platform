# G5 서비스 접근키 경계 검증

2026-11-05 로컬 호스트에서 완료 모듈의 현재 내부 HTTP 계약을 검증했다. 운영 코드를 바꾸지 않고 Catalog·Order에 통합 검사 네 클래스를 추가했다. 실제 Spring Security·HTTP·Flyway·MySQL과 격리 Kafka를 사용했으며 업무 서비스 전체 스택이나 실제 금융 동작은 실행하지 않았다.

## 호출 관계와 허용 범위

| 호출자 → 수신자 | HTTP 헤더 | 허용 경로·메서드 |
|---|---|---|
| Store → Catalog | X-Store-Service-Key | GET /internal/v1/store-products 및 /{code} |
| Order → Catalog | X-Order-Service-Key | GET /internal/v1/order-products/{code} |
| Download → Catalog | X-Download-Service-Key | GET /internal/v1/products/{code}/build |
| Settlement → Catalog | X-Settlement-Service-Key | GET /internal/v1/products/{code}/owner |
| Payment → Order | X-Payment-Service-Key | POST /internal/v1/orders/{orderNo}/payment-admissions 및 /{admissionId}/failure |

키 값은 검증 JVM에서 SecureRandom의 32바이트 난수로 생성하고 Base64 URL 문자열로 표현했다. 서로 다른 호출 관계에 서로 다른 테스트 값을 사용했다. 원문 키를 이 문서나 결과 요약에 기록하지 않았다.

## 양성·음성 대조

- Catalog는 유효한 출시 스냅샷을 기존 PublishProjectRelease 서비스로 저장했다. 올바른 키의 목록·상품·빌드·소유자 조회는 실제 데이터의 200과 Cache-Control: no-store를 요구한다. 단순한 401/403 이외 응답을 성공으로 보지 않는다.
- 키 누락·빈 값·잘못된 값은 401이다. 다른 관계의 실제 테스트 키를 기대 헤더에 넣은 경우, 다른 관계의 헤더/키를 넣은 경우, 올바른 값을 다른 관계의 헤더에 넣은 경우도 각각 401이다.
- 올바른 Catalog 키라도 POST·PUT·PATCH·DELETE, 선언하지 않은 내부 경로 및 관리자 공개본 작업 조회는 401이다. 서비스 키가 사용자 ADMIN 권한으로 취급되지 않음을 확인한다.
- 서버 쪽 네 Catalog 키를 모두 비워 재기동한 별도 테스트 컨텍스트에서도 빈 값과 임의 값을 거절했다.
- Payment→Order는 실제 PENDING 주문을 Order 도메인 모델·리포지토리로 저장했다. 정상 키의 접수는 실제 PAYMENT_PROCESSING과 200을 만들고, 같은 키의 검증된 실패 기록은 PENDING 복귀와 200을 만든다. 응답의 no-store도 확인했다. 이 테스트에서만 Catalog 조회 포트를 정해진 공개 상품 픽스처로 대체했으며 PG 승인을 호출하지 않았다.
- Payment 키의 누락·오류·다른 값·다른 헤더와 유효한 서명의 사용자 ADMIN JWT만 보낸 요청은 접수/실패 기록 모두 401이다. 잘못된 메서드·다른 내부 경로·사용자 주문 조회에도 키만으로 접근할 수 없다. 거절 후 주문은 PENDING이며 Catalog 포트 호출도 없음을 확인했다.
- Order 서버 키가 빈 별도 테스트 컨텍스트에서도 두 POST 경로를 거절했다.
- 검증 요청의 응답 본문·헤더와 OutputCaptureExtension으로 캡처한 수신 서버의 기본 애플리케이션 로그에 생성한 키 원문이 없는지 검사했다. 실패 시 키 원문을 assertion 메시지로 출력하지 않도록 boolean 판정만 사용했다.

## 실행과 결과

관련 단위·아키텍처 검사:

```sh
./gradlew :apps:catalog:test :apps:order:test --no-daemon --max-workers=2
```

최종 집중 통합 검사:

```sh
STOVE_TEST_MINIO_IMAGE=stove/minio:review-withdrawal-20261103 \
./gradlew :apps:catalog:integrationTest --tests 'com.stove.catalog.*ServiceKey*IntegrationTest' \
  :apps:order:integrationTest --tests 'com.stove.order.*ServiceKey*IntegrationTest' \
  --no-daemon --max-workers=2
```

이번 키 검사 자체는 MinIO를 사용하지 않는다. 실행 호스트에 이미 존재하는 공급 이미지를 지정해 기본 integrationTest의 MinIO 소스 재빌드 의존성을 제외했다. 다른 호스트에서는 준비된 공급 이미지 이름을 사용한다. --tests는 각 모듈 태스크 뒤에 각각 지정해야 한다.

| 최종 XML 범위 | tests | failures/errors/skipped |
|---|---:|---|
| Catalog 단위·아키텍처 | 76 | 0/0/0 |
| Order 단위·아키텍처 | 388 | 0/0/0 |
| Catalog 새 접근키 통합 | 16 | 0/0/0 |
| Order 새 접근키 통합 | 4 | 0/0/0 |

최종 집중 통합 실행은 종료 코드 0·BUILD SUCCESSFUL이다. 전체 check/build·Order migrationTest를 실행한 결과로 확대하지 않는다. 신규 통합 소스의 HTTP 서버 바인딩은 127.0.0.1이며 포트는 임의 할당이다.

최초 명령에서 기본 MinIO 소스 재빌드가 불필요하게 시작돼 이번 작업의 Go 빌드 컨테이너만 중단했다. 공급 이미지 지정 후 실행은 성공했고, 그때 각 태스크의 --tests 지정 차이로 Catalog 기존 통합을 포함한 66건도 실행됐다. 최종적으로 모듈별 필터와 최종 소스를 다시 확인한 16+4건의 XML을 위 표에 기록했다. 초기·반복·캐시 결과를 중복 합산하지 않는다.

검증 전후 Docker 컨테이너 ID·상태와 볼륨 ID를 비교해 기존 컨테이너 23개·볼륨 57개가 일치함을 확인했다. 검증 컨테이너는 종료됐다. 운영 코드·기존 업무 데이터·PG 금융 상태는 변경하지 않았다.

## 완료와 남은 범위

위 다섯 호출 관계의 수신 HTTP 접근키 경계를 완료했다. 서로 다른 키를 설정했다는 전제의 검증이며, 중복 키 설정을 자동 금지하는 기능을 새로 추가하지 않았다. 키 교체·만료·구키/신키 동시 허용, 실제 배포의 비밀 저장·분배, 다른 로그 레벨·프록시·외부 로그 수집기, 외부 PG 키·S3 권한 전체까지 검증한 것은 아니다. Gateway의 내부 경로 미노출과 실제 업무 소유권은 기존 별도 검증 기록을 따른다. G5 전체와 외부 웹훅·경보·백업/복구는 미완료로 유지한다.
