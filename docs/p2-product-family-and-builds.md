# P2 상품 관계와 플랫폼별 빌드

이 단계는 상품 관계와 플랫폼별 릴리스 산출물을 연결한다. 기존 단일 빌드 요청은 그대로 동작한다.

## 상품 관계

`POST /api/v1/studio/games`는 `productKind`(`BASIC`, `DEMO`, `DLC`, `EDITION`, `BUNDLE`)를 받는다. `DEMO`·`DLC`·`EDITION`은 같은 창작자의 `BASIC` 프로젝트를 `parentGameId`로 지정한다. `EDITION`에는 `editionName`이 필요하고 `DEMO` 가격은 0이어야 한다. `BUNDLE`은 `bundleGameIds`에 중복되지 않는 프로젝트 ID를 둘 이상 지정한다. 관계와 역방향 묶음 조회는 `GET /api/v1/studio/games/{gameId}/family`에서 확인한다.

공개 릴리스의 관계는 Catalog와 Store 조회에 투영된다. 다만 현재 주문·라이선스 모델은 무료 DEMO 청구와 BUNDLE 구성품 권한 지급을 지원하지 않는다. 이 두 종류는 릴리스 후에도 일반 판매 상태로 올리지 않으며 운영용 판매 시작 API도 거절한다. 구매·권한 지급은 후속 P2 단계에서 별도로 연결해야 한다.

## 빌드 변형과 델타

업로드 세션의 `platform`은 `WINDOWS`·`MACOS`·`LINUX`, `architecture`는 `X86_64`·`ARM64`이다. 델타 패치 파일은 선택적인 `deltaFromVersion`을 지정해 업로드한다. 해당 문자열은 대상 `productVersion`과 달라야 한다.

`POST /api/v1/studio/projects/{gameId}/submissions`의 기존 `buildId`는 기본 전체 빌드이다. 선택적인 `additionalBuildIds`에 다른 OS·아키텍처의 전체 빌드와 델타 빌드를 추가한다. 모든 빌드는 동일 프로젝트·대상 버전의 검증된 파일이어야 하고, 각 델타에는 같은 OS·아키텍처의 전체 빌드가 포함되어야 한다. 심의 제출물에 묶인 빌드 집합은 변경되지 않으며 릴리스 직전에 모두 smoke test를 통과해야 공개된다.

`GET /api/v1/downloads/{productCode}/manifests`는 릴리스의 변형 목록을 보여준다. 티켓 요청에 `platform`, `architecture`를 함께 지정하면 해당 전체 빌드가 선택된다. `fromVersion`까지 지정하면 일치하는 델타가 있을 때 그 파일을 선택하고, 없으면 같은 대상의 전체 빌드로 돌아간다. 예: `GET /api/v1/downloads/GAME-001/ticket?platform=WINDOWS&architecture=X86_64&fromVersion=1.0.0`. 인증·보유권 검사는 기존과 동일하다.

델타 생성 및 클라이언트 적용·복구 로직은 이 서비스의 책임 밖이다. 업로드된 델타 파일의 내용상 호환성은 빌드 QA에서 확인해야 한다. 전체 빌드를 항상 함께 등록해 서버 측 폴백 경로를 보장한다.
