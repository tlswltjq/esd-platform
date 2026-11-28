# API 데모

[문서 목록](README.md) · [개발 환경과 실행](getting-started.md) · [인증·권한 계약](security.md)

전체 흐름을 자동으로 확인하려면 [여정 자동 검증](#full-demo)을 돌린다. 손으로 호출해 보려면 [창작·심사·출시](#creator-demo) → [구매·보유·다운로드](#purchase-demo) 순서로 따라 한다. 업무 의미와 필드는 [도메인·모델 안내](domain/model-guide.md), 요청·응답 형식은 Swagger UI와 [서비스 명세](services.md)가 정본이다.

재작성 전의 전체 데모(`scripts/api-demo.sh`, 결제 시뮬레이터, 할인·월 마감 여정)는 걷어냈다. 그때의 절차와 실행 기록은 이 파일의 git 이력에 있다.

<a id="full-demo"></a>

## 여정 자동 검증

`:e2e`의 여정 작업은 테스트가 스택을 직접 띄운다(인프라는 Testcontainers, 앱은 `bootJar` 프로세스). 따로 띄워 둘 것이 없고, 끝나면 스스로 내린다. Docker와 JDK 21이 필요하다.

```bash
python3 tools/ci/gradle_result.py -- ./gradlew :e2e:ownershipE2eTest
```

| 보고 싶은 것 | 작업 |
|---|---|
| 가입·로그인, 구매 한 건, 본인·역할별 접근 격리 | `:e2e:ownershipE2eTest` |
| 프로젝트·썸네일·ZIP 업로드·작업본 편집 | `:e2e:studioE2eTest` |
| 심사 요청·승인·반려, 재시작 뒤 결과 전달 | `:e2e:reviewE2eTest` · `:e2e:reviewRestartE2eTest` |
| 출시 확정·재출시·철회 | `:e2e:catalogE2eTest` · `:e2e:withdrawalE2eTest` |
| 공개 상품이 Store 사본에 닿음 | `:e2e:storeE2eTest` |
| 주문·결제·보유권·다운로드(모듈별, 재시작 포함) | `:e2e:orderE2eTest` · `:e2e:paymentE2eTest` · `:e2e:licenseE2eTest` · `:e2e:downloadE2eTest` |
| 운영 조치(환불·판매 중지·다운로드 제한) | `:e2e:g4OperationsE2eTest` |
| License가 멈췄을 때 다운로드가 닫힌 채 실패함 | `:e2e:chaosTest` |
| Jib 이미지 스택에서 구매 여정, 요청 추적 | `python3 tools/verification/verify-jib.py` (`--tracing`) |

작업별 범위와 소요, 실행 전후 Docker 상태 대조는 [테스트 안내](testing.md#scope-index)를 따른다.

<a id="creator-demo"></a>

## 창작·심사·출시 수동 실습

[로컬 스택](getting-started.md#로컬-스택과-swagger)을 띄우고 [Swagger UI](http://127.0.0.1:18080/swagger-ui.html)에서 로그인해 호출한다. 창작·심사는 `studio` 범위를 요청한다.

CREATOR 가입·Project 생성 → 썸네일·전체 ZIP 업로드 → 상점 내용 편집 → 단일 심사 승인 → 명시적 상점 등록 → 수정·재심사·재등록 순서다. ZIP은 manifest나 특정 플랫폼 실행 파일을 요구하지 않는다. 심사 중과 등록 처리 중에는 수정할 수 없고, 새 작업본의 등록이 끝날 때까지 이전 공개 내용이 유지된다. 파일 크기 초과는 413 `PAYLOAD_TOO_LARGE`다.

<a id="purchase-demo"></a>

## 구매·보유·다운로드 수동 실습

PG 어댑터와 외부 모의 PG는 걷어냈다([결정 remove-pg-adapters](decisions.md#remove-pg-adapters)). Payment는 주문을 받으면 프로세스 안에서 바로 승인하므로([결정 in-process-pg-approval](decisions.md#in-process-pg-approval)) 결제창·시뮬레이터 호출·콜백 단계가 없다. 주문을 만들고 `PAID`가 될 때까지 조회하면 된다.

먼저 위 절차로 KRW 상품 하나를 출시한다. 구매자는 `POST /api/v1/auth/signup/member`로 만들고 `commerce` 범위로 로그인한다. 아래는 `:e2e`의 `PurchaseJourney`가 밟는 순서 그대로다.

| 순서 | 호출 | 확인 |
|---|---|---|
| 1 | `GET /api/v1/storefront/products/{productCode}` | 출시가 Store 사본에 닿으면 200(비동기라 잠시 걸린다) |
| 2 | `GET /api/v1/products/by-code/{productCode}` | `price`, `contentVersion` |
| 3 | `POST /api/v1/orders` — `requestId`(UUID), `productCode`, `expectedContentVersion`, `expectedAmount` | `orderNo` |
| 4 | `GET /api/v1/orders/{orderNo}` | `status`가 `PAID`가 될 때까지 다시 조회 |
| 5 | `GET /api/v1/payments/{orderNo}` | 결제 기록 |
| 6 | `GET /api/v1/storage` → `POST /api/v1/storage/{licenseId}/registration` | 보관함의 항목을 라이브러리에 등록 |
| 7 | `GET /api/v1/library/{productCode}` | 200이면 보유 |
| 8 | `GET /api/v1/downloads/{productCode}/ticket` | ZIP을 받을 만료 URL |

남의 주문·결제·라이브러리 항목은 404, 권한 없는 역할은 403이다([인증·권한 계약](security.md)). 환불과 운영 조치의 호출 순서는 `:e2e`의 `G4OperationsFlowTest`를, Settlement가 매출을 기록하지 않는 현재 상태는 [결정 settlement-consumption-off](decisions.md#settlement-consumption-off)를 본다.
