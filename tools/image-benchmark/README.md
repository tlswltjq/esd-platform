# 과거 이미지 비교 재현

`run.py`는 고정 revision의 Dockerfile/Jib 비교를 재현하는 원격 실행기다. 현재 소스의 일반 빌드 도구가 아니다. `worker.py`, `runtime.py`는 해당 작업 디렉터리로 전달하며 `report.py`는 저장된 결과를 읽는다.

```bash
python3 tools/image-benchmark/run.py --help
python3 tools/image-benchmark/report.py runs/image-benchmark/실행-ID
```

기준 revision과 재현 순서는 [이미지 비교 기록](../../docs/jib-oci-comparison.md)을 따른다. 현재 개발·검증은 로컬 OrbStack에서 수행한다. 이번 이동에서는 원격 벤치마크를 재실행하지 않았다.
