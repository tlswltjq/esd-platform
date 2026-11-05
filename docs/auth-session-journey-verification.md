# Auth·Gateway 계정 변경 후 세션 정리 검증

2026-11-05 로컬 호스트에서 Auth·Gateway 실행 JAR, 전용 MySQL 8.0·Redis 7을 연결해 검증했다. Auth requirements R6·R7, Gateway requirements R3·R4와 두 모듈 tasks의 G5 세션 정리 범위에 대응한다.

## 실행과 격리

```sh
./gradlew :apps:auth:bootJar :apps:gateway:bootJar --no-daemon --max-workers=2
python3 scripts/verify-auth-session-journey.py
```

검증 스크립트는 `KIROCREW_SCRATCH` 아래 실행별 로그·결과·자원 목록을 저장한다. Docker와 Python requests, Java 21이 필요하다. 실행별 MySQL·Redis 컨테이너와 네 개의 루프백 포트를 사용하며, Auth·Gateway도 `127.0.0.1`에만 바인딩한다. 테스트 계정과 클라이언트 자격증명은 전용 환경에서만 사용한다. 기존 컨테이너·볼륨을 재사용하거나 시작하지 않는다.

검증용 BFF access token은 25초로 설정했다. Redis 토큰의 만료 정보를 조작하지 않고 실제 시간이 Gateway의 갱신 구간에 도달할 때까지 기다렸다. 운영 기본 수명 15분을 변경한 검증이 아니다. 브라우저 역할은 쿠키와 리다이렉트를 처리하는 HTTP 클라이언트로 수행했다. 화면 렌더링 검사는 포함하지 않는다.

## 결과

| 여정 | 확인한 경계 | 결과 |
|---|---|---|
| 비밀번호 복구 | 실제 BFF 로그인 → 복구 요청 HTTP와 해시 저장 → 재설정 HTTP → 전체 Refresh Token 계열 폐기 → 갱신 실패 401 → Redis 토큰·브라우저 세션 삭제 → 다음 요청도 401 | 통과 |
| 계정 잠금 | 실제 BFF 로그인 → ADMIN의 Gateway 상태 변경 HTTP → 수행 주체별 STATUS_CHANGED 감사 → 전체 계열 폐기 → 갱신 실패 401 → Redis 토큰·세션 삭제 → 다음 요청도 401 | 통과 |
| 역할 회수 | CREATOR 보유 계정 BFF 로그인 → ADMIN의 Gateway 역할 회수 HTTP → 수행 주체별 ROLE_CHANGED 감사 → 전체 계열 폐기 → 갱신 실패 401 → Redis 토큰·세션 삭제 → 다음 요청도 401 | 통과 |

모의 메일 어댑터는 원본 복구 토큰을 노출하지 않는다. 실제 복구 요청으로 토큰 해시 저장을 확인한 뒤, 재설정 HTTP 호출에는 전용 DB에 추가한 알려진 토큰의 SHA-256 해시 픽스처를 사용했다. 따라서 요청 메일에서 원본을 회수한 종단 간 검증이나 실제 메일 발송 성공으로 해석하지 않는다. 메일 포트에서 원본을 캡처하는 기존 `PasswordRecoveryIntegrationTest`·`TokenRotationIntegrationTest`와 검증 경계를 구분한다.

첫 실행은 검증 스크립트가 원문 UUID 세션 쿠키를 Base64로 해석해 중단했다. 서비스 결함으로 집계하지 않았다. UUID와 Base64 쿠키를 구분하도록 수정한 다음 실행에서 위 세 여정이 통과했다. 초기 실행과 재실행 모두 정리 후 기존 자원 목록이 일치했다.

성공 실행은 여정 관측 38개와 별도 자원 보존 대조를 통과했다. 이 관측 수를 JUnit 테스트 수에 더하지 않는다. Auth·Gateway Java 코드는 이번 연결 검증 때문에 수정하지 않았다. 기존 작업의 JUnit 결과와 이번 bootJar·실제 HTTP 여정의 결과를 구분한다.

## 정리와 범위

성공 실행의 결과는 `auth-journey-564d6f25/results.json`이다. 동일 실행 디렉터리의 `before.json`·`after.json`을 비교해 기존 컨테이너 23개와 볼륨 57개의 식별자 목록이 일치함을 확인했다. 실행한 Java 프로세스와 전용 컨테이너는 종료했다. 로그와 결과는 세션 scratch 자료이므로 장기 보존 근거는 이 문서와 저장소의 재실행 스크립트다.

계정 복구·상태 변경 후 갱신과 세션 정리의 최소 여정 검증을 완료했다. Gateway의 모든 업무 API 역할 여정, 외부 웹훅, 서비스 자격증명, 다중 Gateway 장애·복구, 공통 운영 전체와 실제 메일 연동을 완료한 것은 아니다.
