# 기존 원격 환경 관리

현재 코드·테스트·서비스 실행 기준은 로컬 OrbStack이다. 이 도구는 기존 SSH·rsync 환경 관리를 위해 보존한다. 접속 정보는 `stove.remote`, `stove.remotedir` 로컬 Git 설정 또는 `STOVE_REMOTE`, `STOVE_REMOTE_DIR`에서 읽는다.

```bash
bash tools/remote/remote.sh --help
```

`demo` 명령은 제거했다. `gate`는 현재 Compose 프로젝트의 필수 서비스를 검사한다. `e2e`는 기존 이미지를 사용해 격리 Jib 검증을 실행한다. `stack up infra`는 인프라 상태만 검사한다. 기존 자료·볼륨 보존과 동시 사용 검사 정책은 유지한다. 도구 이동 검증에서는 원격 연결·동기화·배포를 수행하지 않는다.
