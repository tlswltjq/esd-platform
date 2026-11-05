# Gateway 최신 API 권한 경계 검증

2026-11-05 로컬 호스트에서 실제 Auth·Gateway 실행 JAR과 전용 MySQL·Redis를 연결해 검증했다. Gateway requirements R1·R2·R3·R4·R5의 역할 경계와 전달 계약, tasks의 G4/G5 권한 검증 부분에 대응한다.

## 실행

```sh
./gradlew :apps:auth:bootJar :apps:gateway:bootJar --no-daemon --max-workers=2
python3 scripts/verify-auth-session-journey.py --roles
```

Java 21·Docker·Python requests와 `KIROCREW_SCRATCH` 설정이 필요하다. 기존 세션 정리 스크립트에 `--roles` 모드를 추가했으며, 인자가 없으면 기존 복구·잠금·역할 회수 검증을 수행한다. 경로별 기대 권한은 `scripts/gateway_role_journey.py`의 CASES에 명시했다. 실행 시 Gateway 설정에서 기대값을 추출하지 않는다.

Auth와 Gateway는 실제 실행 JAR을 사용하며 JWT 디코더를 목으로 바꾸지 않는다. 기밀 OAuth 클라이언트의 실제 BFF 로그인으로 세션 쿠키·서명된 access token을 얻는다. 역할 검증에서는 운영 기본과 같은 900초 수명을 사용했다. 하위 업무 9개는 서로 다른 루프백 포트의 HTTP 픽스처를 사용한다. 모든 서비스와 픽스처는 `127.0.0.1`에만 바인딩한다.

## 역할별 실제 계정

| 계정 | Auth 발급 역할 | 준비 |
|---|---|---|
| member | MEMBER | 공개 회원 가입 |
| creator | MEMBER·CREATOR | 공개 창작자 가입 |
| reviewer | REVIEWER | 전용 환경 bootstrap |
| admin | ADMIN·REVIEWER | 전용 환경 bootstrap |

각 계정의 실제 `/bff/session` 역할 집합을 먼저 확인했다. ADMIN이 MEMBER·CREATOR 권한을 자동으로 가진다고 가정하지 않았으며, 창작자의 MEMBER 역할은 함께 적용했다.

## 확인한 범위

43개 method/path 조합을 각 계정의 쿠키와 Bearer 두 방식으로 호출한 344개 조합 및 익명 43개 조합이 통과했다. 대상은 공개 상점·상품·이미지, 주문·환불 재시도/재심사, 결제창·승인·시도 실패, 라이브러리·다운로드, Studio 심사/출시 요청, 판매자 판매/공개 노출 설정·프로모션·본인 정산, 심사자 승인/반려, 관리자 공개본 작업·상품 제한·정산·환불·승인 중단·재심사 판정·취소 보류·지연 조사다.

- 허용 요청은 정확히 200, 올바른 목적 서비스의 표식과 HTTP 호출 수 증가를 요구했다. 단순히 401/403이 아니거나 연결 실패 5xx라는 이유로 통과시키지 않았다.
- 거절 요청은 정확히 403과 하위 호출 수 불변을 함께 확인했다. 보호된 경로의 익명 요청은 인증/CSRF 경계에서 401 또는 403으로 거절됐다.
- 쿠키 요청에서 실제 JWT가 하위 HTTP로 릴레이되고 Bearer 요청에서도 토큰이 보존됨을 확인했다. 관련 commerce·상품 제한 라우트는 위조한 `X-Member-Id`를 제거했다.
- 결제 승인 HTTP 픽스처의 202·400·404·409·422·503 상태, JSON 코드와 Cache-Control을 보존했다. 202의 PENDING 본문과 Location도 확인했다. 이는 해당 업무 상태를 실제로 생성한 검증이 아니다.
- 실제 로그인 쿠키의 변경 요청에서 CSRF를 빼면 403이며 하위 호출이 없음을 확인했다.
- 관리자 Bearer로 가격 재계산 POST·ops·internal 경로, 잘못된 문서 메서드·문서 하위 경로를 호출해 하위 업무에 도달하지 않음을 확인했다. 라우트 자체의 부재는 기존 `GatewayRouteTest`의 별도 구조 검증과 구분한다.
- 계정 상태 변경·역할 부여·회수 세 경로는 픽스처가 아닌 실제 Auth로 호출했다. ADMIN은 존재하지 않는 대상의 업무 404를 받고, 다른 세 계정은 Gateway에서 403으로 차단됐다.

## 결과와 보존

실행 `auth-journey-ab89936f`는 관측 622개를 모두 통과하고 종료 코드 0으로 끝났다. 관측에는 로그인·역할·중계·헤더 등의 복수 단언이 포함된다. 622를 독립 API 수 또는 JUnit 사례 수로 해석하거나 기존 JUnit 수와 더하지 않는다.

실행 디렉터리의 results.json과 before.json·after.json을 대조했다. 기존 컨테이너 23개와 볼륨 57개의 식별자 목록이 일치했고 전용 컨테이너·Java 프로세스·HTTP 픽스처를 종료했다. 서비스 운영 코드와 기존 공유 자료는 변경하지 않았다. 세션 scratch는 임시 자료이므로 재현 방법과 장기 보존 근거는 저장소의 스크립트와 이 문서다.

## 완료 판정의 경계

Gateway의 최신 역할 허용·거절, 실제 JWT/세션 사용, 목적 서비스 도달과 오류/진행 응답의 투명 전달 검증을 완료했다. 하위 업무는 HTTP 계약 픽스처이므로 실제 업무 DB의 소유권·결제·환불·심사 상태 전이, 외부 PG·메일·웹훅을 끝까지 실행한 검증으로 확대하지 않는다. 따라서 G4의 실제 업무 전체 여정과 G5의 서비스 자격증명·공통 운영 전체 항목은 미완료로 유지한다. 기존 복구·상태 변경 후 실제 Redis 세션 정리 검증은 `auth-session-journey-verification.md`를 따른다.
