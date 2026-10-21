# 명시적 자료 전환

현재 신규 DB 초기화는 `infra/mysql/init`과 각 모듈 Flyway가 담당한다. 이 디렉터리는 기존 자료의 특정 버전 전환용이다. 파일 이동은 공유 DB 전환 완료를 의미하지 않는다.

| 파일 | 적용 대상·조건 |
|---|---|
| `catalog/catalog-transition.sql` | 구 Catalog 쓰기·릴레이 중지 후 V10 → V11 Outbox 보존·격리 |
| `order/order-transition.sql` | 구 Order 쓰기·릴레이 중지 후 구 Outbox 보존·격리 |
| `payment/payment-transition.sql` | 구 Payment 쓰기·릴레이 중지 후 구 Outbox 보존·격리. Kafka 시작점 별도 점검 |
| `license/license-merge-preflight.sql` | Order V10·License V4 전환 전 읽기 전용 점검. 현재 스키마에 그대로 실행하지 않음 |
| `store/store-schema.sql` | 기존 개발 DB에 Store 스키마·권한 추가 |
| `mock-pg/migrate.py` | Payment V11 내장 PG 결정의 외부 PG 복사. 쓰기 중지, 기존 행 일치 확인, 비용 미산정 유지 |

각 모듈 guide의 전환 절차와 SQL 헤더를 먼저 확인한다. `python3 tools/migrations/mock-pg/migrate.py --help`로 입력을 확인한다. PG 이관은 환경 변수 `SOURCE_MYSQL_PASSWORD`, `TARGET_MYSQL_PASSWORD`를 사용한다. 이번 정리에서는 실제 자료 전환을 실행하지 않는다.
