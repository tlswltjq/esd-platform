# 관측 자료 수집

업무 데이터를 수정하지 않고 CSV를 수집한다. 실행 중인 서비스의 actuator 접근과 Docker/Prometheus 접속이 필요하다. 기본 HTTP 주소는 로컬 Compose 포트이며 격리 검증 스택은 주소를 명시한다.

```bash
bash tools/observability/collect-outbox.sh runs/outbox.csv
bash tools/observability/collect-consumer.sh runs/consumer.csv
LAG_GROUPS=license-paid-order bash tools/observability/collect-lag.sh runs/lag.csv
bash tools/observability/watch-alerts.sh --out runs/alerts.csv --duration 60
```

앞의 세 수집기는 종료 신호까지 반복한다. 출력 상위 디렉터리를 먼저 준비한다. `INTERVAL`은 수집 간격이다. `ORDER_ACTUATOR`, `CONSUMERS`(공백으로 구분한 `이름=URL`), `KAFKA_CONTAINER`, `KAFKA_BOOTSTRAP`, `LAG_GROUPS`, `PROM`, `LAG_GROUP`으로 대상을 지정한다. `watch-alerts`의 기본 그룹은 `license-paid-order`다. Download는 Kafka를 사용하지 않아 소비자 기본 목록에서 제외했다.

부하 생성·장애 주입·데이터 초기화는 포함하지 않는다. 과거 수집 결과 해석은 [성능 기록](../../docs/performance.md)을 참고한다.
