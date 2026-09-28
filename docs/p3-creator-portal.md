# P3-2 창작자 게임 등록·관리 포털

`/studio/`는 창작자용 정적 포털이다. 화면은 게이트웨이가 제공하고, 실제 소유권·상태 전이는 Studio API가 JWT의 `CREATOR` 역할과 개인 workspace로 검증한다.

## 사용 흐름

1. `studio-web` PKCE 로그인 후 `/api/v1/studio/games`에서 내 프로젝트만 조회한다.
2. BASIC 게임을 만들고 상점 초안과 HTTPS 자산 URL을 저장·게시한다.
3. KRW 가격 revision과 한국 등급 설문 revision을 만든다.
4. Windows ZIP을 multipart presigned URL로 업로드하고 `VALIDATED` 결과를 기다린다.
5. 게시된 상점·가격·등급 revision과 검증된 build를 immutable submission snapshot으로 제출한다.
6. 제출별 `RATING`, `STORE_PAGE`, `BUILD_QA`, `LEGAL`, `SDK_COMPLIANCE`, `COMMERCIAL` 게이트와 피드백을 확인한다. 변경 요청이면 새 revision으로 다시 제출한다.
7. 모든 게이트가 승인된 `READY_FOR_RELEASE` 제출물을 즉시 또는 `Asia/Seoul` 예약 시각으로 출시하고, 게시된 릴리스는 rollback할 수 있다.

## API 경계

포털 조회용 목록 API도 프로젝트·제출물·릴리스의 workspace 소유권을 먼저 확인한다.

- `GET /api/v1/studio/projects/{gameId}/store-page-revisions`
- `GET /api/v1/studio/projects/{gameId}/pricing-revisions`
- `GET /api/v1/studio/projects/{gameId}/rating-revisions`
- `GET /api/v1/studio/projects/{gameId}/submissions`
- `GET /api/v1/studio/projects/submissions/{submissionId}/review-status`
- `GET /api/v1/studio/projects/{gameId}/releases`

운영 심사자의 `/api/v1/reviews/**` 경로를 창작자에게 열지 않고, Studio가 이벤트로 투영한 `SubmissionGate`만 창작자 응답으로 노출한다. 따라서 외부 검수 조작 API와 창작자 피드백 조회 API가 분리된다.

## 실행

게이트웨이와 인증 서버가 실행 중이면 다음 주소에서 포털을 연다.

```text
http://localhost:8080/studio/
```

`studio-web`의 기존 `http://localhost:3000/callback` redirect URI는 OIDC E2E 호환성을 위해 유지하고, 로컬 게이트웨이의 `/studio/` redirect URI를 함께 등록한다. 기존 JDBC client도 시작 시 새 scope와 redirect URI를 보존적으로 합친다.
