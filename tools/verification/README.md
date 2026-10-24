# 검증 도구

완료된 모듈의 로컬 검증 구현을 모은다. Java 인수 테스트는 `e2e/`, 스택 실행 스크립트는 `scripts/`에 둔다.

저장소 루트에서 실행한다.

```bash
python3 tools/verification/verify-settlement.py
python3 tools/verification/verify-jib.py --help
```

검증 명령은 `tools/verification/`으로 통일했다. 이전 `scripts/` 호환 래퍼는 제거했다. 실행 조건은 [실행 안내](../../docs/getting-started.md)와 모듈별 검증 문서를 따른다.

| 위치 | 책임 |
|---|---|
| `verify-*.py` | 모듈별 격리 스택 및 Jib 이미지 검증 |
| `verify-tracing.py` · `support/tracing_verification.py` | `--foundation-only`로 공통 기반을 먼저 검증하고, 기본 실행으로 Payment 재시작·장애·추적 복원을 검증 |
| `auth-bff.py` | 실행 중인 스택의 Auth/BFF 검증. [필수 설정](../../docs/auth-bff.md) 필요 |
| `support/minio_image.py` | 검증용 MinIO 이미지 준비 |
| `support/mock_pg_runtime.py` | 독립 DB를 사용하는 외부 모의 PG 실행·종료 |

검증 도구는 인프라와 앱을 실행하고 검증 데이터를 생성한다. 이번 분리는 코드 위치와 진입점만 정리하며, 각 검증의 포트·DB·컨테이너 격리, 결과 저장 위치, 정리 절차는 유지한다. 검증 로직 수정은 이 디렉터리에서 수행한다.

## 분리 후 실행 검증 (2026-10-21)

- 로컬 OrbStack에서 당시 호환 래퍼를 통해 `verify-jib.py --skip-build`로 실행했다.
- 앱 12개의 이미지 식별값·헬스체크·실행 사용자·JVM 옵션·PID 1, 구매·다운로드, 배분·소유자 격리, 지급 명세 수령·중복 없는 재시도, 12개 앱 정상 종료를 통과했다.
- 격리 스택을 정리하고 기존 컨테이너가 보존됐음을 확인했다. 결과는 `runs/jib-validation-ldrycrmu/result.json`과 같은 디렉터리의 로그·JUnit 결과에 저장했다.
- 최초 이미지 재빌드는 Docker Hub DNS 조회 실패(`UnknownHostException`)로 중단됐다(`runs/jib-validation-fb7kqel0/images.log`). 실행 검증에는 기존 로컬 이미지를 사용했으며, 새 이미지 빌드 성공을 의미하지 않는다.

## 실행 상태 검사

`python3 tools/verification/stack-status.py`는 기본 로컬 스택에서 완료 모듈에 필요한 인프라와 앱(외부 PG·PG DB 포함)을 기다린다. 다른 Docker 프로젝트의 상태는 검사하지 않는다. 헬스체크가 없는 서비스는 실행 여부만 확인하며 API·브로커 기능 검증을 대신하지 않는다.

별도 Compose 스택은 `-p PROJECT -f COMPOSE_FILE`로 지정한다. `--service NAME`으로 대상을 좁히고, 성공 종료해야 하는 초기화 작업은 `--one-shot NAME`으로 지정한다. 컨테이너를 변경하거나 시작하지 않는다.

래퍼 제거·상태 검사 교체 후 `python3 -B tools/verification/verify-jib.py --skip-build`도 통과했다. 최신 결과는 `runs/jib-validation-7hmyv5ve/result.json`, 전체 도구 정리 검증은 [조사·실행 기록](../../docs/scripts-tools-audit.md)을 따른다.
