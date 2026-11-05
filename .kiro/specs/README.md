# ESD Platform — 스펙 (SDD)

이 폴더는 esd-platform을 Kiro SDD(Spec-Driven Development) 구조로 재정립한 명세다.
기존 `docs/`의 계획·검증·안내 문서는 근거 자료로 보존하고, 여기서는 **현재 계약과 남은 작업**을 모듈 단위로 정리한다.

## 구조

모듈 하나당 폴더 하나, 안에 세 파일이 있다.

| 파일 | 다루는 것 |
|---|---|
| `requirements.md` | 무엇을·왜. EARS 형식 수용 기준 |
| `design.md` | 어떻게. 아키텍처·설계 결정·데이터·API·이벤트 계약 |
| `tasks.md` | 완료/남음 체크리스트. 일반화 단계(G1~G6) 연결 |

## 모듈 (11개)

비즈니스 흐름 순서다 — 창작(A) → 구매(B) → 이용·정산(C), 그리고 공통(Auth·Gateway).

| 흐름 | 모듈 | 한 줄 책임 |
|---|---|---|
| 공통 | [auth](auth/) | 계정·역할·OAuth2/OIDC 토큰 |
| 공통 | [gateway](gateway/) | 라우팅·BFF 로그인·세션·토큰, 내부 API 차단 |
| A | [studio](studio/) | 출시 상세·빌드·심사/출시 요청 |
| A | [review](review/) | 불변 스냅샷 수동 승인·반려 |
| A | [catalog](catalog/) | 출시 처리·공개 상품/공개본 정본 |
| B | [store](store/) | 공개 상품 검색·조회 사본 |
| B | [order](order/) | 주문 스냅샷·청구액·결제 결과·환불 |
| B | [payment](payment/) | 결제 접수·PG 승인/조회·환불 실행 |
| C | [license](license/) | 상품 이용 권한·구매 근거·라이브러리 |
| C | [download](download/) | 보유권·공개 빌드 확인, ZIP 서명 URL |
| C | [settlement](settlement/) | 매출 배분·수수료·월 마감·PG 대사 |

## 현재 목표

MVP 전 기능을 **일반적인 사용 수준으로 고도화**한 뒤, 엣지 케이스·성능으로 추가 고도화한다.
단계(G1~G6)와 모듈별 공백은 각 `tasks.md`와 `docs/platform-generalization-plan.md`를 따른다.

11개 모듈 모두 MVP 재작성은 완료했다(2026-10-13~10-18). MVP 완료는 일반화 완료와 구분한다.
