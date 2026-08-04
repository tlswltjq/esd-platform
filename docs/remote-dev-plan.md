# 원격 실행 환경 계획 — 기존 OCI 인스턴스를 CI 로

로컬 머신이 이 스택을 돌릴 수 없다. 빌드·테스트·측정을 이미 보유한 OCI 인스턴스로 옮기고,
프로젝트가 성숙하면 같은 인스턴스에 CD 를 얹는다.

아직 실행하지 않은 계획이다. 실행이 끝나면 이 문서는 사라지고
[decisions.md](decisions.md) 의 결정 항목과 README 의 진입 경로 표로 접힌다.
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

**실행 방향** — `docker-compose.yml` 의 인프라 9종 위에 `docker-compose.apps.yml` 의 앱 10종을
얹어야 전체 흐름이 돈다. 앱 컨테이너는 `MaxRAMPercentage=75` 로 잡혀 있다.
인프라만으로 3.83GB 가 거의 찬다.
**전체 스택을 로컬에서 한 번도 동시에 띄워본 적이 없다** — 그것이 지금 막혀 있는 일이다.

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

---

## 1. 결정 — 진입 경로에 D 를 더한다

decisions.md 11번의 표에 한 줄이 붙는다.

| 경로 | 호스트 가정 | 용도 |
|---|---|---|
| A. devcontainer (docker-in-docker) | 없음 | 아무것도 못 믿을 때 |
| B. `scripts/dev.sh` (호스트 소켓) | Docker Desktop / OrbStack | 빌린 머신, 빠름 |
| C. 로컬 직접 (`./gradlew`) | JDK + Docker 있음 | 주 랩탑, 단위 테스트·ArchUnit |
| **D. OCI self-hosted CI** | **없음 (push 하면 돈다)** | **전체 스택 통합 테스트, 성능 측정, 나중에 CD** |

A·B·C 는 전부 "이 머신의 Docker 를 어떻게 빌리는가"의 변주였다.
셋 다 3.83GB 라는 같은 천장 아래 있다. D 는 천장을 바꾼다.

**A·B·C 를 지우지 않는다.** 단위 테스트와 ArchUnit 규칙은 로컬이 빠르고 CI 를 기다릴 이유가 없다.
D 는 **인프라가 필요한 것만** 가져간다. 경계는 "무엇이 컨테이너를 요구하는가"다.

---

## 2. 인스턴스 — 이미 있다. 그리고 비어 있지 않다

`ssh sng` (Ampere A1, 2026-08-04 실측):

| | 값 |
|---|---|
| 아키텍처 | **aarch64** |
| OS | Ubuntu **20.04.6** LTS |
| CPU / RAM | 4 코어 / **23GB** |
| 디스크 | 194GB (9GB 사용, **185GB 여유**) |
| Docker | 28.1.1 / Compose v2.35.1 |
| swap | **0** |
| JDK | **없음** |

**아키텍처** — arm64 다. 주 랩탑도 M1(arm64)이므로 지금 로컬에서 도는 이미지 조합이
그대로 여기서 돈다. 아키텍처 리스크는 낮다.

**Docker 28** — 그대로 쓴다. `testcontainersVersion=1.21.4` 가 API 1.40 하한을 넘으므로 문제없다.

> `.devcontainer/devcontainer.json` 은 Docker 를 28 로 고정하며
> "Testcontainers 를 올릴 수 있게 되면 이 고정을 풀 수 있다"고 적어두었다.
> **그 조건은 이미 충족됐다.** devcontainer 쪽 고정 해제는 별건으로 분리한다.

### 지금 무엇이 돌고 있나

| 프로젝트 | 컨테이너 | 상태 |
|---|---|---|
| `sng_server` | api-gateway, auth, trickcal, webshop, auth-db(MySQL), game-db(MySQL), webshop-redis | 전부 healthy, 3주~5일 가동 |
| `oci-infra` | traefik v3.0 — **0.0.0.0:80 / 0.0.0.0:443** | 6주 가동 |

**`api.trickcal.online` 을 서비스하는 라이브 시스템이다.**
named 볼륨에 DB 데이터가 있다 — `sng_server_auth_db_data`, `sng_server_game_db_data`,
`sng_server_mysql_data`, `sng_server_mysql_master_data`, `sng_server_postgres-data`,
`sng_server_postgres-master-data`.

---

## 3. 결정 — 기존 워크로드를 내리지 않는다

처음 구상은 "기존 컨테이너를 전부 내리고 CI 전용으로 쓴다"였다. **측정이 그 전제를 깼다.**

### 근거 1 — 내려서 얻는 게 2.5GB 뿐이다

| | |
|---|---|
| 컨테이너 8개 **실사용 합계** | **2.5GB** |
| 컨테이너 8개 `mem_limit` 합계 | 11.9GB (상한이지 예약이 아니다) |
| 시스템 available | **19GB** |

stove 를 막고 있던 것은 로컬의 3.83GB 천장이다. 여기는 **안 내려도 19GB 가 놀고 있다.**
라이브 서비스를 2.5GB 때문에 내리는 것은 수지가 맞지 않는다.

메모리 상한은 컨테이너마다 이미 걸려 있다(webshop 1G, trickcal 3G, gateway 512M,
auth 768M, redis 384M, auth-db 2G, game-db 4G, traefik 256M). 남의 스택이 폭주해
CI 를 굶길 위험은 이미 막혀 있다.

### 근거 2 — 포트 충돌이 하나뿐이다

호스트 LISTEN: `80`, `443`, `22`, `111`(rpcbind), `53`(resolved), `127.0.0.1:8080`(traefik 대시보드).

sng 앱과 DB 는 호스트로 포트를 publish 하지 않는다 — 전부 traefik 뒤에 있다.
**3306 · 6379 · 9092 · 9200 · 27017 · 9000 · 9001 · 9090 · 3000 · 8081~8089 전부 비어 있다.**
stove 와 겹치는 것은 `8080` 하나다.

그리고 **CI 에서는 포트 publish 가 필요 없다.**
Testcontainers 는 랜덤 포트를 쓰고, compose 통합 테스트는 컨테이너 네트워크 안에서 끝난다.
`docker-compose.ci.yml` override 로 `ports:` 를 비우면 충돌은 0 이 된다.

> 이건 편의가 아니라 보안이다. 이 호스트는 공개 IP 를 가진다.
> MySQL·Elasticsearch·MinIO 를 `0.0.0.0` 에 publish 하는 구성을 여기 올려서는 안 된다.
> 로컬 compose 의 고정 포트는 **로컬 개발 편의**였고, 그 가정은 여기서 성립하지 않는다.

### 근거 3 — 되돌리기 어렵다

`down` 자체는 되돌릴 수 있다. 그러나 정리 과정에서 `down -v` 나 `docker volume prune` 이
한 번 섞이면 위 6개 볼륨의 DB 데이터는 복구할 수 없다. `~/db_backups` 의 최신 백업은
**6월 21~22일** 것이다 — 6주 전이다.

**결정 — 공존한다.** 대신 공존의 조건을 명시적으로 건다(4장).

---

## 4. 공존의 조건

### 조건 1 — swap 을 만든다

지금 **swap 이 0** 이다. 이 상태에서 메모리 스파이크는 곧바로 커널 OOM killer 다.

```sh
sudo fallocate -l 8G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo sysctl vm.swappiness=10   # 스파이크 흡수용, 상시 스왑 유도 아님
```

디스크가 185GB 남는다. 사실상 공짜다.

### 조건 2 — CI 에 메모리 상한을 건다 *(핵심)*

기존 컨테이너에는 상한이 있고 **CI 작업에는 없다.**
Gradle 데몬과 Testcontainers 스택이 스파이크를 치면 OOM killer 가 발동하는데,
**무엇이 죽을지는 `oom_score` 가 정한다 — 라이브 서비스가 뽑힐 수 있다.**

상한은 CI 쪽에 건다. 사고가 CI 안에서 끝나야 한다.

```ini
# /etc/systemd/system/actions.runner.<...>.service.d/limit.conf
[Service]
MemoryMax=10G
MemoryHigh=8G
CPUQuota=300%
```

Testcontainers 가 띄우는 컨테이너는 러너 cgroup 밖에서 뜨므로
`docker-compose.ci.yml` 과 Testcontainers 쪽에도 별도로 제한이 필요하다.
Phase 3 에서 실측하며 정한다.

### 조건 3 — 격리가 없다는 것을 기록한다

self-hosted 러너는 docker 그룹 권한을 요구하고, **docker 그룹은 사실상 root 다.**
CI 가 침해되면 같은 호스트의 라이브 DB 까지 간다.

- 리포가 **PRIVATE** 이므로(확인함) fork PR 이 러너에서 임의 코드를 실행하는 경로는 없다.
  **공개 리포였다면 이 구성은 금지다** — GitHub 이 명시적으로 경고하는 시나리오다.
- 실용적으로 수용한다. 본인 리포, 본인 코드다.
- **버린 선택지** — CI 전용 인스턴스 분리. 격리는 얻지만 Always Free 한도를 넘고,
  용량 확보 싸움을 다시 해야 한다. 얻는 격리보다 비용이 크다.
- 완화 — 러너를 `ubuntu` 가 아닌 전용 사용자로 돌리고, 워크스페이스를 홈 밖에 둔다.

### 조건 4 — 남는 것을 치운다

self-hosted 러너는 GitHub-hosted 와 달리 **매번 깨끗한 상태로 시작하지 않는다.**

- 워크스페이스에 `build/` 가 누적된다
- Testcontainers 가 남긴 컨테이너·이미지·볼륨이 쌓인다
- 185GB 는 커 보이지만 이미지 레이어는 빠르게 는다

주기적 `docker system prune` 과 워크스페이스 정리를 러너 설치와 **같은 단계에** 넣는다.
나중에 붙이면 디스크가 찬 뒤에 붙이게 된다.

---

## 5. CI — self-hosted runner

### 왜 러너인가

목적이 "빌드가 도는 원격 머신"이 아니라 **CI 환경**이다. 그러면 답은 하나다.
`ci.yml` 이 이미 있고, 바뀌는 것은 `runs-on` 한 줄이다.

```yaml
runs-on: [self-hosted, linux, ARM64]
```

이 한 줄이 `ubuntu-latest`(2코어 / 7GB, Testcontainers 병렬 불가)를
4코어 / 23GB 로 바꾼다. Phase 2 의 전체 스택 통합 테스트가 여기서 가능해진다.

### 손볼 곳

| 항목 | 지금 | self-hosted 에서 |
|---|---|---|
| `actions/setup-java@v4` | 매번 JDK 21 설치 | 호스트에 미리 설치하고 뺀다 (arm64 Temurin) |
| `gradle/actions/setup-gradle@v4` | GitHub 캐시 왕복 | `~/.gradle` 이 그냥 남는다. 캐시 업로드가 낭비 |
| `org.gradle.workers.max` | 2 (로컬 값) | **4** — 러너의 `~/.gradle/gradle.properties` 에. 리포는 안 고친다 |
| 정리 | 러너가 매번 새것 | 조건 4 |
| 동시 실행 | 무제한 | 러너 1대 = 직렬. `concurrency` 가 이미 있다 |

**러너는 1대로 시작한다.** 2대를 등록하면 병렬이 되지만 메모리 위험이 배가 된다.
sng 리포의 워크플로와도 같은 호스트를 나눠 쓰게 되므로, 직렬이 안전한 출발점이다.

리포의 `gradle.properties`(워커 2 / 힙 2g)는 **그대로 둔다.**
그 값은 Docker 3.83GB 머신의 값이고, 그 머신은 여전히 존재한다. 머신별 값은 머신에(10번).

---

## 6. CD — 이미 있는 패턴을 따라간다

같은 인스턴스에 `~/SNG_server/deploy-module.sh` 라는 **선례가 있다.**
pull → `compose up --wait --force-recreate` → readiness 실패 시 직전 태그로 자동 롤백.
바퀴를 다시 만들 이유가 없다.

다만 sng 의 `deploy.yml` 은 GitHub-hosted 에서 빌드 → `scp-action` → `ssh-action` 이다.
**self-hosted 로 옮기면 scp/ssh 단계가 통째로 사라진다** — 러너가 그 호스트다.
빌드한 자리에서 바로 배포한다. 비밀(HOST/USERNAME/KEY)도 필요 없어진다.

stove 쪽 형태:

```
push → self-hosted 러너에서 build + test
     → bootJar → 이미지 빌드 → ghcr.io/tlswltjq/stove-<module>
     → 같은 호스트에서 compose up --wait (traefik 라벨로 라우팅)
```

**CD 는 지금 결정하지 않는다.** 앱 10 + 인프라 9 를 상주시키면 메모리 계산이 완전히 달라진다.
그 계산은 Phase 3 에서 실측이 나온 다음에 한다. 지금은 "경로가 있다"까지만 확인하고 둔다.

---

## 7. 그럼 MCP 는 — 뒤로 민다

처음 계획은 MCP 서버를 앞에 뒀다. **순서가 틀렸다.**

CI 가 self-hosted 로 서면 "push → 결과" 경로가 생긴다.
MCP 와 `scripts/remote.sh` 가 채우려던 자리는 그것과 다르다 —
**커밋하기 전의 반복 루프**다(고쳤다 → 5초 뒤 결과). CI 는 커밋을 요구하므로 그 루프에 못 쓴다.

그런데 그 루프가 얼마나 답답한지는 **CI 를 써보기 전에는 모른다.**
통합 테스트를 하루에 세 번 돌린다면 push 로 충분하다. 서른 번이면 MCP 가 필요하다.

**결정 — CI 를 먼저 세우고, 답답해지면 그때 만든다.**
만들게 되면 설계는 그대로 유효하다:

- 로직은 `scripts/remote.sh` 에. MCP 서버는 로직 0 — 스크립트 호출 + 출력 정형화만.
  사람이 손으로 못 돌리는 경로는 고장난 줄도 모르게 고장난다(12번).
- MCP 를 쓰는 진짜 이유는 **출력 상한** 하나다. 그냥 `ssh` 로도 명령은 똑같이 돈다.
  차이는 실패한 빌드 로그 4만 줄이 컨텍스트에 들어오느냐다.
  `remote_test` 는 `{apps,common}/*/build/test-results/test/TEST-*.xml` 을 파싱해
  실패 클래스·메서드·메시지·스택 3줄만 낸다.
- 빌드가 분 단위이므로 `async → job_id → 폴링` 이 필수다.

---

## 8. 단계와 수용 기준

각 단계는 **통과 조건이 있어야 다음으로 간다.**

### Phase 0 — 인스턴스 확보 ✅ 완료
이미 있다. 2장이 실측 기록이다.

### Phase 1 — 안전장치 *(무엇을 올리기 전에)*
1. **백업** — 최신이 6주 전이다. sng DB 볼륨을 한 번 뜬다.
2. **swap 8GB** (조건 1)
3. **JDK 21 (arm64 Temurin)** 설치 — 지금 호스트에 java 가 없다
4. 러너 전용 사용자 + 워크스페이스 위치 결정 (조건 3)

**통과 조건** — `free -h` 에 swap 8G, `java -version` 이 21, 백업 파일이 오늘 날짜.
**기존 컨테이너 8개는 여전히 healthy.** 이 조건은 이후 모든 단계에 붙는다.

### Phase 2 — 러너 등록
`actions-runner-linux-arm64` 설치, systemd 서비스 + `MemoryMax` 드롭인(조건 2),
`docker system prune` 타이머(조건 4).

**통과 조건** — `gh api repos/tlswltjq/stove/actions/runners` 에 online 러너 1대.
hello-world 워크플로가 통과.

### Phase 3 — `ci.yml` 전환 *(이 계획의 1차 목적)*
`runs-on` 교체, setup-java 제거, 러너 `~/.gradle/gradle.properties` 튜닝,
`docker-compose.ci.yml`(ports 제거) 추가.

**통과 조건** — `./gradlew build` 가 러너에서 그린. 소요 시간을 `ubuntu-latest` 와 비교 기록.
빌드 중 `free -h` 최저 available 을 측정해 조건 2 의 상한을 확정한다.

### Phase 4 — 전체 스택 통합 테스트
인프라 9 + 앱 10 을 러너에서 동시에 올린다. 로컬에서 한 번도 못 한 것.

**통과 조건** — 컨테이너 19개 healthy, 게이트웨이를 통해
`GameRegistered → ReviewApproved → ProductChanged` 가 한 번 관통.
**0장의 메모리 추정을 실측으로 교체한다.**

### Phase 5 — performance.md 재측정 *(이 계획이 갚는 빚)*
`scripts/perf/` 를 전체 스택 위에서 다시 돌린다. 뺀 것 없이, 스와핑 없이.
`pageouts` 와 여유 메모리를 측정 로그에 같이 남긴다.

**통과 조건** — 릴레이 활성/비활성 두 구성이 **서로 다른 숫자**를 낸다.
같은 숫자면 여전히 포화이고, 그때는 인스턴스가 아니라 부하 설계를 의심한다.
performance.md 의 "재측정이 남아 있다"를 지운다.

### Phase 6 — (선택) `remote.sh` + MCP
7장의 조건이 성립하면. 성립하지 않으면 만들지 않는다.

### Phase 7 — CD
6장. Phase 4 의 실측이 나온 뒤에 설계한다.

### Phase 8 — 접기
`docs/decisions.md` 에 결정 항목 추가, README 진입 경로 표에 D 추가,
`.devcontainer` 의 낡은 Docker 28 고정 주석 정리. **이 문서는 지운다.**

---

## 9. 위험

| 위험 | 정도 | 대응 |
|---|---|---|
| **OOM 이 라이브 서비스를 죽인다** | **높음** | swap + CI cgroup 상한 (조건 1·2). Phase 1 이 이것 때문에 있다 |
| **CI 침해 → 라이브 DB** | 중간 | 격리 없음을 수용하되 기록(조건 3). PRIVATE 리포가 전제 |
| 볼륨 오삭제 | **높음** | Phase 1 의 백업. `-v`·`prune` 은 이 계획에서 쓰지 않는다 |
| 디스크 누적 | 중간 | 조건 4 를 러너 설치와 같은 단계에 |
| **Ubuntu 20.04 EOL** | 중간 | 표준 지원 2025-04 종료. 공개 443 호스트다. **별건으로 판단 필요** |
| 러너 직렬화로 대기 | 낮음 | 러너 1대로 시작. 느리면 그때 2대 |
| arm64 비호환 | 낮음 | 주 랩탑이 M1 이라 같은 조합이 이미 돈다 |
| 인스턴스가 유일한 실행처 | 중간 | 경로 A·B·C 유지. 로컬 단위 테스트는 계속 로컬에서 |

---

## 10. 결정이 필요한 것

1. **기존 워크로드** — 3장의 "공존" 판단에 동의하는가.
   내리는 쪽을 고수한다면 근거를 알아야 한다(자원이 아닌 다른 이유가 있는지).
2. **Ubuntu 20.04** — EOL 상태로 계속 갈 것인가. 별건으로 다룰 것인가.
3. **러너 실행 사용자** — `ubuntu` 재사용 / 전용 사용자 신설 (조건 3).
4. **착수 범위** — Phase 1(안전장치)까지 / Phase 3(CI 그린)까지 / Phase 5(재측정)까지.
