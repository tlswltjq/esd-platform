# 문서 안내

개발·검증 명령은 [tools 안내](../tools/README.md), 정리 범위는 [scripts 조사·실행 기록](scripts-tools-audit.md)을 따른다. 과거 성능·장애 실험 결과는 해당 기록 문서에 보존한다.

현재 개발 목표·모듈 평가·권장 순서는 [전체 기능 일반화 계획](platform-generalization-plan.md)을 우선한다. 전액 환불의 상세 작업을 이어갈 때는 [공통 지시](refund-next-session.md) → [현재 인계](refund-session-handoff.md) → [PG·환불 계약과 검증 기록](pg-test-integration-plan.md) 순서로 읽는다. [과거 세션 이력](history/refund-session-through-2026-10-28.md)의 대기 상태는 현재 상태가 아니다.

처음 읽을 때는 [도메인·모델 안내](domain/model-guide.md) → [업무 흐름](domain/workflows.md) → 필요한 모델 사전 순서로 읽는다. 모델 사전에는 필드뿐 아니라 생성·사용 시점, 업무 규칙 제안과 코드·테스트 근거가 함께 있다.

## 업무와 코드 이해

| 문서 | 다루는 내용 |
|---|---|
| [판매 중지·재개와 관리자 제한](catalog-sales-policy-plan.md) | 판매자/관리자 독립 권한, 기존 주문·승인 접수, Store 사본과 로컬 OrbStack 검증 |
| [상품 다운로드 제한·저장소 적용](download-restriction-plan.md) | 판매/개인 이용권과 독립한 관리자 제한·과거 URL 차단·실패 재시도 |
| [상품 검색·목록 노출](catalog-listing-policy-plan.md) | 독립 노출·판매 기반 AUTO 기본값·판매자/관리자 권한·Store 사본과 전체 동기화 |
| [공개 상세 B와 새 주문 차단](public-product-detail-plan.md) | 숨긴 상품의 상세/새 주문 404·내부 업무 조회 분리·C 확장 경계 |
| [프로젝트별 단일 심사·명시적 철회 분석](project-review-withdrawal-analysis.md) | 심사·출시 전체 흐름, 최신 철회 정책·검토 대안과 구현·검증 순서 |
| [단일 심사·출시 철회 구현·검증](project-review-withdrawal-verification.md) | 진행 중 심사/출시 각각 1건, 내구 철회·자동 공개·재시작과 OCI 695건·실제 여정 3건 |
| [G4 출시·상점·보유자 운영 여정](g4-operations-verification.md) | 9개 서비스의 업데이트·철회·판매/노출/다운로드 제한·Store 복구와 남은 목록/운영 공백 |
| [Studio 목록 페이지 계약](studio-list-pagination-plan.md) | 직접 전환 A·번호 방식 A·DB 페이지/총건수·일괄 조회와 OCI 103건·실제 여정 10단계 |
| [전체 기능 일반화 계획](platform-generalization-plan.md) | 현재 사용자 목표, 환불·Payment 평가, 열한 모듈의 공백·권장 실행 순서·일반화 완료 조건 |
| [도메인·모델 안내](domain/model-guide.md) | 전체 지도, 모델 사전 연결, 한 게임의 출시·구매 사례 |
| [용어집](domain-glossary.md) | 표준 용어 색인, 헷갈리는 상태·식별자·버전 |
| [업무 흐름](domain/workflows.md) | 창작자·심사자·구매자·운영자별 순서와 이벤트 |
| [미결 질문](domain/open-questions.md) | 합의가 필요한 업무 정책, 구현 차이와 검토 기록 |
| [서비스 명세](services.md) | 서비스별 API·상태·이벤트와 외부 어댑터 |

## 실행과 실습

| 문서 | 다루는 내용 |
|---|---|
| [개발 환경과 실행](getting-started.md) | 로컬·원격 실행, Swagger OAuth, 문제 해결 |
| [API 데모](api-demo.md) | 전체 자동 검증과 창작·구매 수동 호출 예제 |
| [인증·권한 계약](security.md) | 계정·역할·소유권, access token과 Payment·License·Download·Settlement MVP 경계 |
| [auth·Gateway/BFF](auth-bff.md) | 재작업 완료 모듈의 자동 로그인·RTR·공유 세션, 실행과 검증 |
| [Studio 실행·심사·출시 요청](studio-guide.md) | Auth·Gateway 기준의 전체 업로드·심사·출시 요청과 세 모듈 검증 |
| [Review 실행·수동 심사](review-guide.md) | 심사자 로그인·ZIP 열람·승인 및 반려·네 모듈 검증과 재시작 확인 |
| [Catalog 실행·출시 검증](catalog-guide.md) | 공개 API, 다섯 모듈 독립 검증, 기존 DB·Outbox 전환 절차 |
| [Store 실행·검색·사본 확인](store-guide.md) | OCI 검증, 제목 검색·상세, HTTP 초기화와 재시작 복구 |
| [Order 실행·주문·결제 결과 확인](order-guide.md) | 단일 상품 주문, 금액 스냅샷·중복 요청·결과 픽스처·OCI 장애와 재시작 검증 |
| [Payment 실행·결제·복구 확인](payment-guide.md) | 모의 PG·내 결제 조회, 응답 유실·결과 롤백·8개 앱 장애와 재시작 검증 |
| [License 실행·라이브러리·재전달 확인](license-guide.md) | 상품 보유권·구매 근거·내 라이브러리, 아홉 앱의 중단·재시작·원본 이벤트 복구 검증 |
| [Download 실행·빌드 다운로드 확인](download-guide.md) | 로컬 OrbStack, 보유권·현재 공개 빌드·실제 ZIP·만료·장애·재시작 |
| [Settlement·외부 모의 PG 실행](settlement-guide.md) | 역할별 키·독립 PG·명세 수집·수령 대사·PG 비용 독립 판매자 월 마감·기존 원장 이관 |
| [Settlement 책임 분리·검증](settlement-separation-verification.md) | G3 첫 단위: 승인 거래·비용·판매자 배분 분리, V8 자료 보존과 OCI 75건 |
| [Settlement 외부 부담 혜택 A·PortOne 조회](settlement-external-benefit-verification.md) | 총액·실결제·외부 혜택 근거 보존, 주문 금액 배분·V9·최신 OCI 84건 |
| [Settlement 환불 배분 계산 정책·인터페이스](settlement-refund-allocation-policy.md) | 원배분 전액 상계 A·정책 조립과 계산 단계 OCI 92건, 정책 인터페이스 적용 범위 |
| [Settlement 환불 월 선택 A](settlement-refund-month-policy.md) | 금융 확인 월·마감 시 현재 OPEN 월 선택, 최초 결과 보존과 정책 단계 OCI 102건 |
| [Settlement 전액 환불 원장·월 합계](settlement-refund-ledger-verification.md) | 금융 확인·독립 PG 취소 조회·불변 원장·월 순액·역순 DLT 복구·V10과 최신 OCI 120건 |
| [Settlement PG 비용·반환·지급 자료 계획](settlement-pg-evidence-plan.md) | 공식 출처·TEST 제약과 사용자가 선택한 토스페이 정산 API 자동 수집 A |
| [Settlement 토스페이 정산 API 수집·대사](settlement-pg-evidence-verification.md) | 전체 페이지·불변 원본/거래·주기 재수집·ADMIN 조회·V11과 OCI 144건, 실제 가맹점/월 요약 후속 범위 |

## 설계·검증·운영 근거

| 문서 | 다루는 내용 |
|---|---|
| [아키텍처](architecture.md) | 서비스 경계, 트랜잭션·이벤트 처리, DB 실험과 설계 위험 |
| [유스케이스 작성 규칙](use-case-conventions.md) | SRP에 따른 책임 분리, 동사형 클래스·메서드 이름, 트랜잭션 유지 기준 |
| [adapter 패키지 변경·완료 기록](adapter-package-rename-plan.md) | 기존 구조 유지·ArchUnit 경계 전환·단위 965건·통합 101건·실제 15단계 OrbStack 검증 |
| [업무 코드 경계 점검](core-boundary-audit.md) | 이벤트 입력·Inbox·검증 책임 분석과 전역 설정 바인딩 경계 개선 |
| [아키텍처 검사 정밀도·적용 범위 점검](architecture-test-audit-20261102.md) | 작업별 선행 커밋, OCI 기준선 477건, 과도한 제약 3유형·검사 공백 8유형과 권장 수정 순서 |
| [아키텍처 검사 정비](architecture-test-improvement-20261102.md) | 공통 등록 묶음·입력/순서/조립/업무 경계 보완, 명명 지침 분리와 OCI 검증 |
| [공통 모듈](common-modules.md) | Web·Security·JPA·Kafka·Messaging 책임, 의존성과 실행 환경 |
| [Studio 단순화 계획·완료 기록](studio-simplification-plan.md) | Project 중심의 전체 빌드 업로드·심사 및 출시 요청·재요청 범위와 완료·검증 기록 |
| [Review 개선 계획·완료 기록](review-simplification-plan.md) | 단일 스냅샷 심사·빌드 열람·승인 및 반려·결과 이벤트의 범위와 완료·검증 기록 |
| [Catalog MVP 계획·완료 기록](catalog-mvp-plan.md) | 출시 처리·공개본·공개 조회의 범위, 버전·중복 정책과 실제 검증 결과 |
| [Store MVP 계획·완료 기록](store-mvp-plan.md) | 학습용 최소 검색·조회 사본, Catalog 연동과 OCI 실제 검증 결과 |
| [Order MVP 계획·완료 기록](order-mvp-plan.md) | 단일 상품 주문 스냅샷·내 주문·결제 결과 계약과 OCI 실제 검증 결과 |
| [Payment MVP 계획·검증 기록](payment-mvp-plan.md) | 모의 PG 자동 처리, Inbox·미확정 재개·결과 Outbox·내 결제 조회와 OCI 완료 근거 |
| [License MVP 계획·완료 기록](license-mvp-plan.md) | 상품별 단일 구매·출처 통합·Order 중복 구매 제한과 OCI 완료 근거 |
| [Download MVP 계획·완료 기록](download-mvp-plan.md) | 무상태 빌드 다운로드·내부 조회 계약과 로컬 OrbStack의 단위·통합·열 앱 검증 |
| [Settlement MVP 계획·완료 기록](settlement-mvp-plan.md) | 외부 PG·Payment 전환·판매자 배분·월 마감·수령 대사. 단위 1,014건·관련 통합 152건·전체 프로세스 32단계 검증 |
| [Tempo 분산 추적 도입·검증 계획](tempo-tracing-plan.md) | 기존 Tempo 활용, Payment 추적 메타데이터 저장·예약 처리 복원, HTTP 전파와 격리 검증 계획 |
| [PG 테스트 연동·환불 계약/검증](pg-test-integration-plan.md) | PortOne TEST, 내구 환불 실행/조회/재송신, 오류 정책, 고객 API·관리자 심사와 실제 자동 환불 근거 |
| [환불 현재 인계](refund-session-handoff.md) · [다음 세션 지시](refund-next-session.md) | 현재 완료/미결 범위, 보존할 실제 검증 환경, 사용자 협업 방식과 다음 작업 |
| [설계 결정](decisions.md) | 선택한 방식과 버린 대안, 결정 당시의 이유 |
| [이벤트 순서](event-ordering.md) | 정상 발행과 재시도·재투입의 순서 보장 조건 |
| [테스트 전략](testing.md) | 단위·통합·계약·E2E에서 검증하는 범위 |
| [Order 기능·권한 단위 테스트](testing.md#order-module-tests) | 주문 생성·멱등·본인 조회·결제 결과·역할 권한의 책임과 검증 범위 |
| [Order 환불 RED 테스트](testing.md#order-full-refund-tdd) | 접수·심사·정지 확인·환불 결과·복구·추적의 시나리오와 TDD 작성 순서 |
| [결함 기록](defects.md) | 재현한 결함, 원인·수정·회귀 검증 |
| [성능 측정](performance.md) · [측정 원칙](measuring.md) | 요약과 원자료, 유효·무효 판정 기준 |
| [Dockerfile·Jib OCI 비교](jib-oci-comparison.md) | 동일 이미지 기반의 빌드·배포·응답 지연·CPU·메모리 측정 |
| [Dockerfile 제거 계획·검증](dockerfile-removal-plan.md) | Jib 기본 전환, MinIO 소스 빌드·패키징과 로컬 검증 |
| [Compose 실행 구성](compose-layout.md) | 기본·로컬 4개 파일, 포트 바인딩과 단순화 검증 |
| [장애 실험](chaos.md) · [재시작 시나리오](resilience-scenarios.md) | 부하 중 장애와 중단 후 처리 재개 근거 |
| [이용권 DB 복구 절차](runbooks/license-db-loss.md) | 재작성 전 계약의 원장 유실 복구 참고 기록. 새 License MVP에 적용하지 않음 |
| [DB 락 실험](../tools/db-lab/README.md) · [성능 도구](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/perf/README.md) · [장애 도구](https://github.com/tlswltjq/esd-platform/blob/e32750ffc95dee1fe5b59b2e248859f84aa77154/scripts/chaos/README.md) | 실험 실행 방법과 결과 파일 |

## 문서를 갱신할 때

모델·속성·업무 규칙은 모델 사전, 미결 정책은 미결 질문, API 계약은 서비스 명세, 실행 명령은 실행·데모 문서에 갱신한다. 다른 문서에서는 해당 설명을 링크한다. 단계명이나 이슈 번호별로 같은 안내를 새로 만들지 않는다.

날짜가 있는 결정·결함·실험 기록은 당시의 근거다. 현재 코드에서 다시 검증하지 않은 결과를 최신 상태로 바꾸지 않는다. 브랜치·열린 PR·푸시 여부는 Git과 이슈에서 확인한다. 사용자가 요청한 환불 세션 인계는 현재 요약 한 곳과 공통 지시 문서에만 유지한다. 단계별 명령·실패·수치는 PG 검증 기록에 모으고 과거 인계는 history/에 보존한다.
