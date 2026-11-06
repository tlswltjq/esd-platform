# 백업과 장애 복구 절차

2026-11-06 사용자는 G5 백업 방식으로 B안을 선택했다. **하루 1번 전체 백업 + MySQL 변경 기록(binlog)의 연속 보관**으로 장애 직전까지 복구한다. 이 문서는 그 절차와 격리 연습 결과를 기록한다. 보관 위치·보관 기간·백업 파일 암호화는 아직 정하지 않았다(아래 "미결 정책").

## 대상

| 저장소 | 담는 것 | 방법 |
|---|---|---|
| MySQL 9개 스키마(한 서버) | 계정·상품·주문·결제·이용권·정산, Auth 서명 키 | 전체 덤프 + binlog 연속 복사 |
| MinIO `stove-builds` | 빌드 ZIP·썸네일 | 삭제 없는 연속 미러 |
| 배포 비밀값 | DB 비밀번호, `PAYMENT_APPROVAL_PAYLOAD_KEY` 등 | 배포 도구의 비밀 저장소. 백업 파일과 따로 보관 |

복구하지 않는 것: Redis 세션(다시 로그인), Prometheus·Tempo 기록, 외부 PG(공급자 소유). MinIO 버킷·정책·계정과 MySQL 계정·스키마 생성은 **프로비저닝**이며 복구 대상 자료가 아니다. 대체 서버는 같은 프로비저닝으로 먼저 만든다.

## 왜 binlog를 계속 복사하나

전체 덤프만 있으면 덤프 이후의 결제는 PG에만 남는다. binlog를 서버 밖으로 계속 복사해 두면 마지막으로 복사된 변경까지 되살린다. MySQL 8은 binlog를 기본으로 남기지만(ROW, 30일) 데이터와 같은 디스크에 있어 디스크 장애에 함께 사라진다.

MinIO도 같은 이유로 연속 미러를 쓴다. DB를 장애 직전까지 되살리면 그 사이 업로드한 빌드도 있어야 한다. 객체 키는 업로드마다 새 UUID라 덮어쓰지 않으므로, 삭제를 따라가지 않는 미러는 복구된 DB 행이 가리킬 수 있는 모든 객체를 가진다.

## 도구

`tools/operations/backup.py`. 클라이언트는 서버와 같은 버전의 컨테이너에서 실행한다. 공식 `mysql:8.0` 이미지에는 `mysqlbinlog`가 없으므로 같은 버전을 더한 이미지를 먼저 만든다.

```bash
./gradlew :infra:mysql-client:jibDockerBuild   # stove/mysql-client:8.0-local
```

비밀값은 환경 변수로만 받고 컨테이너에는 이름으로만 전달한다. 명령 인자와 파일에 남지 않는다.

| 명령 | 환경 변수 | 하는 일 |
|---|---|---|
| `stream-binlog --dest DIR --user backup` | `MYSQL_BACKUP_PASSWORD` | binlog를 `DIR/binlog`에 계속 복사. 재시작하면 마지막 파일부터 다시 받는다 |
| `full --dest DIR --user backup` | `MYSQL_BACKUP_PASSWORD` | `--single-transaction --source-data=2` 덤프와 binlog 위치·SHA-256 메타데이터 |
| `objects-backup --dest DIR --endpoint URL --watch` | `STORAGE_BACKUP_ACCESS_KEY`, `STORAGE_BACKUP_SECRET_KEY` | 버킷을 `DIR/objects`에 계속 미러. `--remove`를 쓰지 않는다 |
| `restore --backup DIR --user root` | `MYSQL_RESTORE_PASSWORD` | 체크섬·binlog 연속성을 먼저 확인하고 덤프 적재 → 덤프 위치부터 모든 binlog 재생 |
| `objects-restore --backup DIR --endpoint URL` | `STORAGE_RESTORE_ACCESS_KEY`, `STORAGE_RESTORE_SECRET_KEY` | 미러를 버킷으로 되돌림 |

공통 옵션: `--host`, `--port`, `--network`(기본 `host`), `--image`.

### 백업 계정 권한

| 계정 | 권한 |
|---|---|
| MySQL `backup` | `SELECT, SHOW VIEW, TRIGGER, LOCK TABLES, EVENT, RELOAD, REPLICATION CLIENT, REPLICATION SLAVE ON *.*` |
| MinIO `build-backup` | 객체 `s3:GetObject`, `s3:GetObjectTagging` / 버킷 `s3:ListBucket`, `s3:GetBucketLocation`, `s3:ListenBucketNotification`. 쓰기·삭제 없음 |

복구는 대체 서버의 관리자 계정으로 한다. binlog 재생에는 백업 계정보다 넓은 권한이 필요하다.

## 평상시 순서

1. `stream-binlog`와 `objects-backup --watch`를 상시 실행한다. 멈추면 그 시간만큼 유실 범위가 늘어난다.
2. 하루 1번 `full`을 실행한다. binlog 복사가 실행 중이어야 덤프 위치 이후가 이어진다.
3. 복사본은 DB·MinIO 서버 밖에 둔다.

## 장애 복구 순서

1. 쓰는 쪽(앱)을 멈춘다.
2. 대체 MySQL·MinIO를 프로비저닝한다(스키마·계정·버킷·정책). 비어 있는지 확인한다.
3. `restore`로 DB를 되살린다. 덤프 체크섬이 다르거나 binlog 번호가 비면 아무것도 바꾸지 않고 실패한다.
4. `objects-restore`로 빌드를 되돌린다.
5. 같은 배포 비밀값으로 앱을 시작한다. 결제 암호화 키가 다르면 암호화된 승인 접수 자료를 읽지 못한다.
6. 확인: 서비스 상태, 테이블 행 수, 다운로드, 진행 중이던 결제의 재개.

**마지막으로 복사된 변경 이후는 잃는다.** 복사 지연(보통 수 초) 사이에 커밋된 결제·지급은 외부 PG와 Kafka에는 있는데 DB에는 없을 수 있다. 복구 직후 PG 거래 조회와 정산의 거래 대사로 우리 기록에 없는 승인을 찾는다. Kafka는 이번 장애 범위에서 살아 있다고 가정한다. 소비 위치가 DB 밖에 있어 그대로 이어진다.

## 격리 연습 (2026-11-06)

`python3 tools/verification/verify-jib.py --backup-restore`

실제 Jib 이미지 12개(앱 11 + 외부 모의 PG)와 전용 MySQL·MinIO·Kafka·Redis에서 다음을 실행했다.

1. binlog·객체 연속 복사 시작 → 구매 전 전체 백업
2. 구매·다운로드, 배분·소유자 격리 E2E 단계
3. 앱 정지 → 복사본이 최신 binlog 위치·모든 객체에 도달할 때까지 대기 → 기대 상태 기록
4. MySQL·MinIO 컨테이너를 데이터 볼륨째 삭제 → 빈 대체 서버 프로비저닝
5. 도구로 복구 → 모든 테이블 `CHECKSUM TABLE EXTENDED`·행 수, 객체 키·크기·SHA-256 비교
6. 앱 재시작 → 정산 지급 명세 수령·중복 없는 재시도 E2E 단계 → 12개 정상 종료

| 항목 | 결과 |
|---|---|
| 테이블·행 | 131개·300행 일치. 덤프 뒤 binlog로만 되살린 행 141개(42개 테이블) |
| 객체 | 5개 일치(모두 연속 미러로만 보관) |
| 복구 뒤 업무 | 정산 단계 통과, 금액 중복 없음 |
| 종료·자원 | 12개 정상 종료. 기존 컨테이너·볼륨 57개 보존 |

같은 날 MySQL 단독 점검으로 최소 권한 백업 계정, BLOB, 갱신·삭제, binlog 파일 전환을 포함한 복구도 체크섬이 일치했다. 도구 단위 테스트는 `tools/operations/test_backup.py`(14건)다.

## 검증하지 않은 것

- Kafka까지 함께 잃는 경우. 이미 보낸(SENT) 이벤트를 소비 전에 잃으면 Outbox 재큐잉 대상이 아니다. 운영 Kafka의 복제 설정이 먼저다.
- 덤프 이전의 특정 시점으로 되돌리기(잘못된 대량 변경 취소). 외부 PG·Kafka와 어긋나므로 별도 절차가 필요하다.
- GTID를 켠 서버, 관리형 DB의 자체 백업.
- 복사본을 다른 호스트로 보내는 전송과 스케줄러(cron·systemd 타이머).

## 미결 정책

1. 복사본 보관 위치(다른 호스트·오브젝트 스토리지)
2. 보관 기간(전체 덤프 수, binlog 보관 기간 — 가장 오래된 덤프보다 길어야 한다)
3. 백업 파일 암호화. 덤프에는 Auth 서명 개인 키·이메일·비밀번호 해시가 있다. 미러는 API로 읽은 원본이라 MinIO 서버 측 암호화를 써도 복사본에는 풀린 빌드가 남는다
