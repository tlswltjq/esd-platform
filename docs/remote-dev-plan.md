# 원격 실행 환경 계획 — OCI 인스턴스 + MCP

로컬 머신이 이 스택을 돌릴 수 없다. 빌드·테스트·실행·측정을 원격으로 옮기고,
그 원격을 개발 도구(Claude Code)에서 직접 다루기 위한 계획이다.

아직 실행하지 않은 계획이다. 실행이 끝나면 이 문서는 사라지고
[decisions.md](decisions.md) 의 결정 항목 하나와 README 의 진입 경로 표로 접힌다.
**증명되지 않은 계획은 결정이 아니다**(12번의 교훈).

---

## 0. 무엇이 막혔나

측정된 사실:

| 항목 | 값 | 출처 |
|---|---|---|
| 호스트 | MacBook Air (M1), 8코어 | `sysctl hw.model hw.ncpu` |
| 호스트 RAM | 8GB | `sysctl hw.memsize` |
| **Docker 가용 메모리** | **3.83GB** | `docker info` (MemTotal) |
| Gradle 워커 상한 | 2 | `gradle.properties` |
| Gradle 힙 | 2GB | `gradle.properties` |

`gradle.properties` 의 주석이 이미 이 한계를 기록하고 있다 —
"코어가 아니라 **Docker 에 준 메모리가 한계선**이다."
워커 수를 코어 수(8)가 아니라 2 로 묶은 것은 성능 선택이 아니라 항복이다.
워커마다 Testcontainers 인프라 스택이 하나씩 뜨고, 8개 스택은 3.83GB 에 들어가지 않는다.

같은 벽이 두 방향에서 온다.

**테스트 방향** — 워커 2개 × (MySQL + Kafka + Redis + ES + Mongo) 스택.
ES 하나가 `-Xms512m -Xmx512m` 이므로 RSS 는 그 위다. 워커 2개가 상한인 이유.

**실행 방향** — `docker-compose.yml` 의 인프라 9종을 띄운 뒤,
`docker-compose.apps.yml` 의 앱 10개를 얹어야 전체 흐름이 돈다.
앱 컨테이너는 `MaxRAMPercentage=75` 로 잡혀 있다. 인프라만으로 3.83GB 가 거의 찬다.
**전체 스택을 로컬에서 한 번도 동시에 띄워본 적이 없다** — 그것이 지금 막혀 있는 일이다.

> 위 두 문단의 컨테이너별 메모리는 이미지 기본값에서 온 추정이다.
> 로컬에서 전체 스택을 띄울 수 없으므로 실측이 없다.
> **Phase 1 의 첫 작업이 원격에서 이 값을 실측해 이 표를 채우는 것이다.**

### 이미 대가를 치른 곳

이건 앞으로의 불편이 아니라 **이미 결과를 망친 문제**다.
[performance.md](performance.md) 가 그 기록이다.

- 측정 환경이 "macOS 8GB / Docker 4GB", 전체 스택을 못 띄워
  **Elasticsearch·MongoDB·Grafana 를 빼고** 최소 구성으로 쟀다.
- 그렇게 얻은 HTTP 경로 수치는 무효였다 — 릴레이를 **완전히 끈** 구성이
  개선 구성과 같은 숫자(44.9 RPS)를 냈다. 차이는 코드가 아니라 환경에서 왔다.
- 직접 원인: `pageouts` 150만, 여유 메모리 32%. **스와핑 중에 잰 값**이다.
- 그 문서의 첫 줄이 지금 상태다 — "HTTP 경로는 측정 환경 문제로 유효한 비교를
  얻지 못했고, **재측정이 남아 있다**."

원격 환경은 편의가 아니라 **미결로 남은 재측정의 전제 조건**이다.
24GB 에서는 전체 스택을 띄운 채, 스와핑 없이, 뺀 것 없이 잴 수 있다.

---

## 1. 결정 — 진입 경로에 D 를 더한다

decisions.md 11번의 표에 한 줄이 붙는다.

| 경로 | 호스트 가정 | 용도 |
|---|---|---|
| A. devcontainer (docker-in-docker) | 없음 | 아무것도 못 믿을 때 |
| B. `scripts/dev.sh` (호스트 소켓) | Docker Desktop / OrbStack | 빌린 머신, 빠름 |
| C. 로컬 직접 (`./gradlew`) | JDK + Docker 있음 | 주 랩탑 |
| **D. 원격 (OCI)** | **ssh 만** | **전체 스택, 병렬 테스트, 성능 측정** |

A·B·C 는 전부 "이 머신의 Docker 를 어떻게 빌리는가"의 변주였다.
셋 다 3.83GB 라는 같은 천장 아래 있다. D 는 천장을 바꾼다.

**A·B·C 를 지우지 않는다.** 단위 테스트와 ArchUnit 규칙은 로컬에서 그대로 빠르다.
D 는 인프라가 필요한 일(Testcontainers 통합 테스트, 전체 스택 기동)만 가져간다.
경계는 "무엇이 컨테이너를 요구하는가"다.

---

## 2. 인스턴스

**OCI Always Free / Ampere A1** — 4 OCPU / 24GB RAM / 블록 볼륨 200GB, **aarch64**.

RAM 이 로컬 Docker 대비 6배다. 이것 하나로 위 두 방향이 다 풀린다.

**아키텍처** — arm64 다. 그런데 **주 랩탑도 M1(arm64)** 이므로
지금 로컬에서 도는 이미지 조합이 그대로 원격에서 돈다. 아키텍처 리스크는 낮다.
x86 이 필요하면 무료 옵션은 E2.1.Micro(1 OCPU / 1GB)뿐인데, 그건 지금 로컬보다 나쁘다.
arm64 로 간다.

**OS** — Ubuntu 24.04. Docker apt 저장소 경로가 가장 단순하다.

**Docker 버전** — 최신(29)을 그대로 쓴다. 고정하지 않는다.

> `.devcontainer/devcontainer.json` 은 Docker 를 28 로 고정하며
> "Testcontainers 를 올릴 수 있게 되면 이 고정을 풀 수 있다"고 적어두었다.
> **그 조건은 이미 충족됐다** — `gradle.properties` 가 `testcontainersVersion=1.21.4` 로
> 올려두었고, 그것이 Docker 29 의 API 1.40 하한을 넘는 버전이다.
> 원격에는 낡은 고정을 옮겨심지 않는다. (devcontainer 쪽 고정 해제는 별건으로 분리)

**네트워크 — 포트를 열지 않는다.**
Grafana, Kafka UI, MinIO 콘솔, Elasticsearch 는 이 구성에서 전부 **인증이 없다**
(`GF_AUTH_ANONYMOUS_ENABLED: "true"`, `xpack.security.enabled: "false"`).
공개 IP 에 노출하면 안 되는 것들이다.
OCI 시큐리티 리스트는 22 만 열고, 나머지는 전부 **SSH 로컬 포워딩**으로 본다.
이 판단은 편의가 아니라 보안 요구사항이다 — 나중에 "잠깐만 열자"로 뒤집지 않는다.

**디스크** — 부트 볼륨 기본 50GB 는 빠듯하다.
인프라 이미지 9종 + 앱 이미지 10종 + Gradle 캐시 + Docker 레이어로 100GB 이상 잡는다.

---

## 3. 소스 동기화

| 후보 | 장점 | 버리는 이유 |
|---|---|---|
| git push/pull | 도구 0개, 이력 남음 | 저장할 때마다 커밋을 요구한다. 반복 루프에 안 맞는다 |
| **rsync over ssh** | macOS 기본 탑재, `--delete`, 필터 | — |
| mutagen / 파일 감시 | 자동, 빠름 | **설치 도구를 리포 요구사항으로 만든다**(12번이 direnv 를 뺀 이유) |

**결정 — rsync 를 기본으로, git 은 남길 것에만.**

- 로컬 → 원격 단방향. 원격에서 소스를 고치지 않는다(고치면 다음 sync 에 지워진다).
- 필터는 `.gitignore` 를 따르고, `build/`·`.gradle/`·`.git/` 은 추가로 제외한다.
- **산출물은 역방향으로 자동 동기화하지 않는다.** `build/` 는 원격에만 존재한다.
  테스트 리포트가 필요하면 그때 명시적으로 가져온다.
- mutagen 을 쓰고 싶은 사람은 개인 설정으로 쓴다. 리포는 요구하지 않는다.

**머신별 값은 리포에 적지 않는다**(10번).
인스턴스 IP·사용자·키 경로는 커밋 대상이 아니다. `~/.ssh/config` 에 별칭을 두고,
스크립트는 `${STOVE_REMOTE:-stove-oci}` 로 계산한다.

```
# ~/.ssh/config — 리포 밖
Host stove-oci
    HostName <instance-ip>
    User ubuntu
    IdentityFile ~/.ssh/oci_stove
```

---

## 4. 실행 계층 — 스크립트가 먼저, MCP 는 껍데기

두 층으로 나눈다. 순서가 중요하다.

```
scripts/remote.sh      ← 로직 전부. 사람이 터미널에서 그대로 돌린다.
        ↑
   MCP 서버            ← 로직 0. 스크립트를 부르고 출력을 정형화할 뿐.
```

**MCP 서버에 로직을 두지 않는다.** 이유는 12번의 교훈 그대로다 —
MCP 만 아는 실행 경로를 만들면, 그 경로는 도구를 통해야만 증명된다.
사람이 손으로 못 돌리는 경로는 고장난 줄도 모르게 고장난다.
스크립트가 계약이고, MCP 는 그 계약의 어댑터다.

`scripts/remote.sh` 서브커맨드:

| 커맨드 | 하는 일 |
|---|---|
| `sync` | 로컬 → 원격 rsync. 바뀐 파일 수 반환 |
| `gradle <args…>` | 원격에서 `./gradlew` 실행 |
| `test [모듈] [필터]` | gradle 래퍼 + JUnit XML 결과 요약 |
| `stack up\|down\|status [infra\|apps\|all]` | compose 기동/정지, 헬스체크 대기 |
| `logs <서비스> [-n] [-g 패턴]` | 컨테이너 로그 |
| `http <메서드> <경로> [본문]` | **원격 안에서** 서비스 호출 (터널 불필요) |
| `tunnel open\|close` | SSH 로컬 포워딩 (8080, 8090, 3000, 9090) |
| `status` | CPU/메모리/디스크, `docker ps`, Gradle 데몬 |
| `job <id>` | 비동기 작업 상태·로그 tail |

요구 도구: bash, ssh, rsync — **macOS 기본 탑재. 리포의 설치 요구는 여전히 0개다.**

---

## 5. MCP 서버 설계

### 도구 표

| 도구 | 입력 | 반환 | 출력 상한 |
|---|---|---|---|
| `remote_status` | — | 인스턴스 자원, 컨테이너 목록 | 2KB |
| `remote_sync` | `dry_run?` | 변경 파일 수, 목록 요약 | 2KB |
| `remote_gradle` | `args`, `async?` | 종료코드 + tail, 또는 `job_id` | 4KB |
| `remote_test` | `module?`, `filter?` | **구조화된 실패 목록** | 6KB |
| `remote_job` | `job_id` | `running\|done`, tail | 4KB |
| `remote_stack` | `action`, `target` | 서비스별 상태/헬스 | 2KB |
| `remote_logs` | `service`, `lines?`, `grep?` | 로그 tail | 4KB |
| `remote_http` | `method`, `path`, `body?` | 상태코드 + 본문 | 4KB |
| `remote_tunnel` | `action` | 열린 포트 목록 | 1KB |

### 설계를 강제하는 제약 두 개

**(a) 빌드가 분 단위다 — 동기 툴콜로는 못 버틴다.**
Testcontainers 가 붙은 전체 빌드는 수 분이 걸린다.
`remote_gradle{async:true}` → `job_id` 즉시 반환, `remote_job` 으로 폴링한다.
원격에서 `nohup … > /tmp/stove-jobs/<id>.log` 로 떼어 돌리므로
ssh 세션이 끊겨도 빌드는 살아 있다.

**(b) 출력 상한이 MCP 를 쓰는 진짜 이유다.**
실패한 Gradle 빌드 로그는 수만 줄이다. 그대로 컨텍스트에 부으면 그 세션은 끝난다.
`remote_test` 는 로그를 반환하지 않는다 —
`{apps,common}/*/build/test-results/test/TEST-*.xml`(JUnit XML)을 파싱해서
**실패 클래스 / 메서드 / 메시지 / 스택 상위 3줄**만 낸다.
전문은 원격 파일로 남고, 반환값에는 경로만 실린다. 필요하면 그때 좁혀서 다시 묻는다.

### 배치

```
tools/mcp-stove-remote/     MCP 서버 (얇다. 스크립트 호출 + 파싱)
.mcp.json                   서버 등록 (리포 루트)
.claude/skills/stove-remote/SKILL.md   언제 어느 도구를 쓰는지
```

**MCP 서버는 리포의 빌드 요구사항이 아니다.**
`tools/` 는 Gradle `settings.gradle` 에 들어가지 않고, 없어도 `./gradlew build` 는 돈다.
node(또는 python)를 요구하는 것은 이 개발 도구 하나뿐이고,
그 요구는 빌드가 아니라 편의에 붙는다. 12번이 그은 선이 여기서도 유효하다.

### MCP 와 Skill 의 경계

- **MCP 서버 = 할 수 있는 일.** 도구 표면, 권한 범위, 출력 형태.
- **Skill = 언제 그 일을 하는가.** 순서와 함정.
  ("고치면 반드시 `remote_sync` 먼저", "ArchUnit·단위 테스트는 로컬이 빠르다 —
  원격은 인프라가 필요한 것만", "`remote_http` 는 터널 없이 되고 `remote_tunnel` 은 브라우저용")

---

## 6. MCP 가 정말 필요한가 — 대안 비교

| 대안 | 되는가 | 잃는 것 |
|---|---|---|
| A. Bash 로 그냥 `ssh` | 된다 | 출력 상한 없음(컨텍스트 폭발), 권한이 Bash 전체로 열림, 비동기 핸들 없음 |
| B. 원격에서 Claude Code 직접 실행 | 된다 | **동기화 문제 자체가 사라진다.** 대신 로컬 IDE 와 분리 |
| C. GitHub Actions 를 빌드 팜으로 | 부분적 | 이미 `ci.yml` 이 있다. 피드백이 분 단위 + 커밋 강제. **성능 측정은 불가** — 공용 러너에서 잰 수치는 로컬 스와핑과 같은 종류의 무효다 |
| **D. MCP 서버** | | 만들고 유지해야 한다 |

**결정 — D. 다만 B 와 C 를 폐기하지 않는다.**

D 를 고르는 근거는 단 하나, **출력 상한**이다.
A 도 명령은 똑같이 돈다. 차이는 실패한 빌드 로그 4만 줄이 컨텍스트에 들어오느냐다.
권한이 `mcp__stove-remote__*` 로 좁혀지는 것과 비동기 핸들은 덤이다.

B 는 폐기하지 않는다 — **큰 리팩터링은 B 가 낫다.**
파일을 수십 개 고치는 작업에서는 동기화가 순수 비용이다. 그때는 ssh 로 들어가서 거기서 작업한다.
C 는 유지한다. CI 는 "이 머신의 흔적이 없는 환경"에서의 교차검증이고(10번), 원격 인스턴스도 하나의 머신이다.

---

## 7. 단계와 수용 기준

각 단계는 **통과 조건이 있어야 다음으로 간다.** 조건 없는 단계는 넣지 않았다.

### Phase 0 — 인스턴스 확보 *(사람이 해야 함)*
OCI 콘솔에서 Ampere A1 4 OCPU / 24GB, Ubuntu 24.04, 부트 볼륨 100GB+.
SSH 키 생성, 시큐리티 리스트는 22 만. `~/.ssh/config` 에 `stove-oci` 별칭.

> **함정** — Always Free Ampere 는 "Out of host capacity" 가 흔하다.
> 가용 도메인을 바꿔가며 재시도하는 것 말고 방법이 없다. 며칠 걸릴 수 있다.

**통과 조건** — `ssh stove-oci uname -m` 이 `aarch64` 를 낸다.

### Phase 1 — 원격 부트스트랩
Docker + Compose 플러그인, Temurin 21, 리포 클론, 첫 전체 빌드.

원격 전용 튜닝은 **원격의 `~/.gradle/gradle.properties`** 에 둔다(리포 아님):

```properties
org.gradle.workers.max=4
org.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8
```

리포의 `gradle.properties`(워커 2 / 힙 2g)는 **그대로 둔다.**
그 값은 Docker 3.83GB 머신의 값이고, 그 머신은 여전히 존재한다. 머신별 값은 머신에(10번).

**통과 조건** — `./gradlew build` 가 원격에서 통과. 소요 시간을 기록한다(로컬 대비 근거).

### Phase 2 — 전체 스택 기동 *(이 계획의 실제 목적)*
인프라 9종 + 앱 10종을 동시에 올린다.

**통과 조건** — 컨테이너 19개가 healthy, `remote_http` 로 게이트웨이를 통해
`GameRegistered → ReviewApproved → ProductChanged` 흐름이 한 번 관통한다.
**0장의 메모리 추정표를 실측으로 교체한다.**

### Phase 2.5 — performance.md 재측정 *(이 계획이 갚는 빚)*
`scripts/perf/` 를 전체 스택 위에서 다시 돌린다.
뺀 컴포넌트 없이, 스와핑 없이. `pageouts` 와 여유 메모리를 측정 로그에 같이 남긴다.

**통과 조건** — 릴레이 활성/비활성 두 구성이 **서로 다른 숫자**를 낸다.
같은 숫자가 나오면 여전히 포화 상태이고, 그때는 인스턴스가 아니라 부하 설계를 의심한다.
performance.md 의 "재측정이 남아 있다"를 지운다.

### Phase 3 — `scripts/remote.sh`
4장의 서브커맨드 구현. 터미널에서 손으로 검증.

**통과 조건** — MCP 없이 모든 서브커맨드가 동작. 이 시점에서 이미 쓸 만하다.

### Phase 4 — MCP 서버
`tools/mcp-stove-remote/`, `.mcp.json` 등록. JUnit XML 파서 + 비동기 job.

**통과 조건** — `/mcp` 에 도구가 뜨고, 실패하는 테스트 하나를 심었을 때
`remote_test` 가 로그 전문이 아니라 **실패 요약만** 반환한다.

### Phase 5 — Skill + 권한
`.claude/skills/stove-remote/SKILL.md`, `.claude/settings.json` 에 `mcp__stove-remote__*` 허용.

**통과 조건** — 새 세션이 안내 없이 "고침 → sync → test" 순서를 스스로 밟는다.

### Phase 6 — 접기
`docs/decisions.md` 에 결정 항목 추가(15번), README 진입 경로 표에 D 추가,
`.devcontainer` 의 낡은 Docker 28 고정 주석 정리. **이 문서는 지운다.**

---

## 8. 위험

| 위험 | 정도 | 대응 |
|---|---|---|
| Ampere 무료 용량 부족 | **높음** | AD 바꿔가며 재시도. Phase 0 이 며칠 걸릴 수 있다 |
| arm64 이미지 비호환 | 낮음 | 주 랩탑이 M1 이라 같은 조합이 이미 로컬에서 돈다 |
| 부트 볼륨 부족 | 중간 | 처음부터 100GB+. 나중에 늘리는 것보다 싸다 |
| 첫 빌드 네트워크 (의존성 + 이미지 수 GB) | 낮음 | 1회성. Gradle 캐시·이미지는 인스턴스에 남는다 |
| 인증 없는 서비스 노출 | **높음** | 22 외 포트를 열지 않는다. 전부 SSH 터널 (2장) |
| 인스턴스가 유일한 실행처가 됨 | 중간 | `ci.yml` 유지가 교차검증. A·B·C 경로도 유지 |
| MCP 서버가 스크립트와 어긋남 | 중간 | 서버에 로직을 두지 않는 것이 대응 자체다 (4장) |

---

## 9. 결정이 필요한 것

1. **인스턴스** — 이미 있나, 새로 만드나. (Phase 0 을 건너뛸 수 있는지)
2. **MCP 서버 언어** — TypeScript(node) / Python(uv). 로컬에 이미 있는 쪽이 낫다.
3. **동기화** — rsync 로 확정해도 되나. (3장 권고)
4. **착수 범위** — 문서까지 / Phase 3(스크립트)까지 / Phase 5(MCP+Skill) 전부.

3번은 되돌리기 쉽다(스크립트 한 곳). 1·2·4 는 답이 있어야 다음이 갈린다.
