"""결정 verification-output-root: 실제 스택 검증 산출물(로그·JUnit·자원 목록)을 둘 위치.

기본은 저장소의 `runs/`(.gitignore 대상)다. 오래 보관하거나 작업 디렉터리 밖에 둘 때만 `ESD_SCRATCH`로 바꾼다.
"""
import os
from pathlib import Path


def output_root(repo, environ=None):
    environ = os.environ if environ is None else environ
    scratch = environ.get('ESD_SCRATCH')
    # PR #259 R3: 절대 경로로 돌려줘 다른 작업 디렉터리(Compose 등)에서도 같은 위치를 본다.
    root = (Path(scratch) if scratch else Path(repo) / 'runs').resolve()
    root.mkdir(parents=True, exist_ok=True)
    return root
