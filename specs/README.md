# ESD Platform — 스펙 (SDD)

이 폴더는 esd-platform의 SDD(Spec-Driven Development) 명세다. **현재 계약과 남은 작업**을 모듈 단위로 둔다. 왜 그렇게 정했는지·검증 결과·이력은 `docs/`에 둔다([AGENTS.md](../AGENTS.md) 2절).

## 구조

모듈 하나당 폴더 하나, 안에 세 파일이 있다.

| 파일 | 다루는 것 |
|---|---|
| `requirements.md` | 무엇을·왜. EARS 형식 수용 기준 |
| `design.md` | 어떻게. 아키텍처·설계 결정·데이터·API·이벤트 계약 |
| `tasks.md` | 완료/남음 체크리스트. 로드맵 단계(M·G1~G7) 연결 |

## 플랫폼 (모듈을 넘는 계약)

[platform](platform/)은 모듈 둘 이상에 걸친 계약의 정본이다([결정 151](../docs/decisions.md#platform-spec)): 모듈 경계와 의존 방향, 이벤트 목록(사실·소유자·소비자·키·순서·멱등), 모듈 간 흐름과 단계별 실패 처리, 돈 불변식과 지키는 곳, 수치 품질 목표, 그리고 그 남은 작업. 모듈 스펙은 자기 상태·API·내부 규칙만 갖고, 이벤트·흐름·불변식은 플랫폼 스펙의 행을 링크한다.

## 모듈 (12개)

비즈니스 흐름 순서다 — 창작(A) → 구매(B) → 이용·정산(C), 그리고 공통(Auth·웹 BFF·Gateway).

| 흐름 | 모듈 | 한 줄 책임 |
|---|---|---|
| 공통 | [auth](auth/) | 계정·역할·OAuth2/OIDC 토큰 |
| 공통 | [web-bff](web-bff/) | 브라우저 입구: BFF 로그인·세션·토큰 보관·릴레이·CSRF |
| 공통 | [gateway](gateway/) | 내부 API 게이트웨이: Bearer 검증, 역할별 경로 규칙·라우팅, 내부 API 차단 |
| A | [studio](studio/) | 출시 상세·빌드·심사/출시 요청 |
| A | [review](review/) | 불변 스냅샷 수동 승인·반려 |
| A | [catalog](catalog/) | 출시 처리·공개 상품/공개본 정본 |
| B | [store](store/) | 공개 상품 검색·조회 사본 |
| B | [order](order/) | 주문 스냅샷·청구액·결제 결과·환불 |
| B | [payment](payment/) | 결제 접수·PG 승인/조회·환불 실행 |
| C | [license](license/) | 상품 이용 권한·구매 근거·라이브러리 |
| C | [download](download/) | 보유권·공개 빌드 확인, ZIP 서명 URL |
| C | [settlement](settlement/) | 매출 배분·수수료·월 마감·PG 대사 |

## 현재 단계

범위는 결정 201로 동결했다. 현재 단계와 백로그는 [로드맵 0절](../docs/roadmap.md#scope-freeze)을 따르고, 모듈 `tasks.md`의 남은 항목은 동결 뒤 백로그다.

MVP 재작성 11개 모듈은 2026-10-13~10-18에 완료했고, 웹 BFF는 결정 223으로 Gateway에서 나눴다. MVP 완료는 일반화 완료와 구분한다.
