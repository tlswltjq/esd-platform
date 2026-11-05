# Gateway·실제 업무 서비스 소유권 검증

2026-11-05 로컬 호스트에서 실제 Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment·License·Download·Settlement 11개 앱을 현재 소스로 빌드하고 격리 실행했다. 업무 HTTP 픽스처를 사용한 앞선 권한 경계 검증과 별도로, 실제 서비스·DB·JWT·BFF 쿠키를 통한 본인/타인 조회와 역할 거절을 검증했다.

## 실행과 격리

```sh
python3 tools/verification/verify-gateway-ownership.py
```

저장소 루트에서 실행한다. Java 21·Docker·Python requests·Gradle 캐시와 `KIROCREW_SCRATCH` 설정이 필요하다. 실행기는 현재 소스의 앱 JAR을 offline 빌드하고 전용 MySQL·PG MySQL·Redis·Kafka·MinIO 및 독립 모의 PG 프로세스를 시작한다. 모든 호스트 노출과 Java HTTP 서버는 `127.0.0.1`에 바인딩한다. 결과·로그·실행별 Compose 파일은 세션 scratch 아래에 둔다.

앱 포트는 기본 offset 20000을 사용한다. `GATEWAY_OWNERSHIP_PORT_OFFSET`으로 변경할 수 있으며, Linux의 임시 연결 포트 범위와 겹치는 값은 거절한다. 기존 컨테이너·볼륨은 재사용하거나 시작하지 않는다. 종료 시 전용 자원을 정리하고 기존 컨테이너 ID·상태와 볼륨 ID 목록을 비교한다.

## 데이터 준비와 검증 범위

회원 두 명과 창작자 두 명은 실제 공개 가입 API로 만들었다. 심사자·관리자는 전용 Auth bootstrap 계정이다. 여섯 계정 모두 실제 OAuth/BFF 로그인과 CSRF 토큰을 사용한다. JWT 디코더나 업무 HTTP 서버를 목으로 바꾸지 않았다.

주문·결제·라이브러리·정산의 구매 근거는 전용 DB에 넣은 두 건의 읽기용 픽스처다. 현재 INFORMATION_SCHEMA로 컬럼을 확인하고 현재 DB 제약을 유지한 채 저장했다. Order/Payment의 PAID, License의 ACTIVE와 정산 매출·PG 거래 스냅샷을 준비했으며 Outbox·Kafka 이벤트를 만들어 주입하지 않았다. 따라서 실제 구매·승인·라이선스 지급·정산 수집의 상태 전이를 수행한 검증이 아니다. 실제 PG나 PortOne 금융 동작은 호출하지 않았다.

Studio 프로젝트 두 건은 실제 API로 생성했다. Catalog의 비공개 판매 설정 변경·공개 출시와 Review의 실제 승인/반려 완료는 이번 범위에 포함하지 않았다.

## 결과

| 경계 | 실제 관측 |
|---|---|
| MEMBER 주문·결제·라이브러리 상세 | 본인 200과 상품 스냅샷 일치, 다른 회원 404. 양방향으로 확인 |
| 회원 ID 위조 | 다른 회원이 소유자의 실제 숫자 ID를 X-Member-Id로 보내도 404 |
| MEMBER 목록 | 각 목록이 본인 픽스처 한 건만 포함. 두 회원 모두 확인 |
| CREATOR Studio | 프로젝트 생성 201, 본인 상세 200, 다른 창작자의 상세 403, 본인 목록만 반환 |
| CREATOR 정산 | 본인 매출 한 건만 반환. sellerSubject 쿼리에 다른 판매자를 넣어도 JWT sub 기준 본인 자료만 반환 |
| REVIEWER·ADMIN 심사 접근 | 실제 Review 목록 200, 존재하지 않는 요청의 승인 호출은 업무 404로 도달 |
| 심사 역할 거절 | MEMBER·CREATOR의 심사 접근 403 |
| ADMIN 운영 조회 | 실제 Order 관리자 환불 목록 200, 정산 전체 조회에서 두 판매자 자료 확인 |
| 운영 역할 거절 | MEMBER·CREATOR·REVIEWER의 관리자 환불 조회 403. CREATOR의 관리자 정산 조회와 MEMBER의 창작자 정산 조회도 403 |
| 익명 | 주문 목록 401 |

성공 실행은 `stove-ownership-check-yjo4wrza`이며 `ownership-results.json`의 success=true, 관측 91개, 실행기 종료 코드 0을 확인했다. 91에는 가입·로그인·스키마·복수 응답 단언이 포함된다. 독립 API 수나 JUnit 사례 수로 해석하거나 기존 JUnit 수와 합산하지 않는다.

처음 실행은 검증 도구가 Studio의 items를 content로 읽어 중단했다. 다음 실행은 기존 실행기의 41087 포트가 임시 연결 포트와 충돌해 Store 시작이 실패했다. Studio의 실제 목록 계약에 맞추고 앱 포트를 임시 범위 밖으로 옮긴 뒤 성공했다. 이 도구/실행 환경 문제를 업무 서비스의 권한 결함으로 집계하지 않는다.

세 실행 모두 종료 후 기존 컨테이너 23개·볼륨 57개와 컨테이너 상태가 보존됐다. 성공 실행의 before.json·after.json이 일치하며, 전용 Java·모의 PG 프로세스와 컨테이너를 종료했다. 업무 운영 코드는 수정하지 않았다. scratch는 임시 자료이므로 장기 기록과 재실행 근거는 이 문서와 저장소의 검증 도구다.

## 완료 경계

실제 업무 서비스의 회원별 조회·창작자별 Studio/정산 격리와 심사/운영 역할 진입 검증을 완료했다. 전체 구매·공개 출시·심사 판정·환불 전이·모든 최신 API·외부 웹훅·서비스 자격증명 검증까지 완료한 것은 아니다. Gateway G4/G5 상위 항목은 미완료로 유지한다.
