#!/usr/bin/env python3
"""PR CI가 빌드할 Gradle 태스크를 바뀐 파일로 정한다(결정 207).

규칙(보수적으로, 모르면 전부 빌드):
- PR이 아니면(main push·수동·예약) 항상 ``build``(전체). main은 매번 전체로 한 번 더 지킨다.
- 바뀐 파일이 전부 ``*.md``이거나 Gradle이 읽지 않는 경로(``NOT_READ_BY_GRADLE``, 결정 216)이면 Gradle을 돌리지 않는다(빈 목록).
  그 경로의 Python 테스트는 build 작업의 별도 단계가 늘 돌린다. 목록은 허용 목록이다 — 모르는 경로는 전체다.
- ``apps/<앱>/`` 아래만 바뀌었으면(``*.md`` 제외) 그 앱들의 ``:apps:<앱>:build``와 ``:common:test:test``.
  앱끼리는 프로젝트 의존이 없지만, ``common:test``의 배포 계약 테스트가 모든 앱의 ``application.yml``을 읽는다
  (``common/test/build.gradle``의 ``inputs.files``, PR #208 R1).
- 결정 ci-scope-leaf-paths: 다른 모듈이 기대지 않는 ``e2e/``·``benchmarks/messaging/``만 바뀌었으면 그 모듈의 ``build``,
  저장소 루트의 ``docker-compose*.yml``만 바뀌었으면 그 파일을 읽는 ``:common:test:test``. 앱 범위와 섞이면 합친다.
- 이름 바꾸기는 옛 경로와 새 경로를 둘 다 본다(``--no-renames``, PR #208 R2).
- 그 밖의 경로(``common/``, 빌드 설정, ``infra/``, ``tools/``, ``.github/`` 등)가 하나라도 있으면 전체.
- 변경 목록이 비었으면(얻지 못했으면) 전체.

예: ``python3 -I -B tools/ci/build_scope.py --event pull_request --base HEAD^1 >> "$GITHUB_OUTPUT"``
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys

FULL = ["build"]
# 앱 파일을 입력으로 읽는 공통 테스트. 다른 공통 모듈이 앱 파일을 읽게 되면 여기에 더한다.
APP_FILE_READERS = ":common:test:test"
_APP = re.compile(r"^apps/([a-z][a-z-]*)/")
# 결정 216: Gradle 빌드가 읽지 않는다고 확인한 경로(2026-11-23 조사: *.gradle의 rootProject.file·inputs).
# Gradle이 읽는 tools/migrations/**(migrationTest 입력)·infra/**(Catalog 정책)·*.gradle은 넣지 않는다.
# 새 경로를 넣기 전에 `git grep -n "tools/<경로>" -- '*.gradle'`로 Gradle 입력이 아닌지 확인한다.
# scripts/는 2026-11-28 조사(같은 grep, common/test 테스트가 읽는 경로)에서 Gradle 입력이 아니었다(결정 ci-scope-leaf-paths).
NOT_READ_BY_GRADLE_PREFIXES = ("tools/ci/", "tools/measurement/", "tools/verification/", ".claude/", "scripts/")
NOT_READ_BY_GRADLE_FILES = (".gitignore",)


def not_read_by_gradle(path):
    if path.endswith(".gradle"):
        return False
    return path in NOT_READ_BY_GRADLE_FILES or path.startswith(NOT_READ_BY_GRADLE_PREFIXES)


# 결정 ci-scope-leaf-paths: 어느 모듈도 의존하지 않는 모듈(2026-11-28 `git grep "project(':e2e')"`·`':benchmarks`로 확인).
# 다른 모듈이 의존하게 되면 여기서 뺀다.
LEAF_MODULES = (("e2e/", ":e2e:build"), ("benchmarks/messaging/", ":benchmarks:messaging:build"))
# 저장소 루트의 Compose 파일은 common/test/build.gradle 의 inputs.files(composeConfigs)만 읽는다.
_ROOT_COMPOSE = re.compile(r"^docker-compose[A-Za-z0-9.-]*\.ya?ml$")


def narrow_task(path):
    """한 모듈의 작업만 돌리면 되는 경로면 그 작업, 아니면 None."""
    for prefix, task in LEAF_MODULES:
        if path.startswith(prefix):
            return task
    return APP_FILE_READERS if _ROOT_COMPOSE.match(path) else None


def decide(event, paths, apps):
    """``event``: GitHub 이벤트 이름. ``paths``: 바뀐 파일(저장소 기준). ``apps``: ``apps/`` 아래 앱 이름 집합."""
    if event != "pull_request" or not paths:
        return list(FULL)
    selected = set()
    for path in paths:
        if path.endswith(".md") or not_read_by_gradle(path):
            continue
        narrowed = narrow_task(path)
        if narrowed is not None:
            selected.add(narrowed)
            continue
        match = _APP.match(path)
        if match is None or match.group(1) not in apps:
            return list(FULL)
        selected.update((":apps:%s:build" % match.group(1), APP_FILE_READERS))
    return sorted(selected)


def output_line(tasks):
    return "tasks=" + " ".join(tasks)


def changed_paths(base):
    # 이름 바꾸기를 삭제+추가로 보아 옛 경로도 분류한다. -z 는 특수 문자가 든 파일 이름을 그대로 둔다.
    output = subprocess.run(["git", "diff", "--no-renames", "--name-only", "-z", base, "HEAD"], check=True,
                            capture_output=True, text=True).stdout
    return [path for path in output.split("\0") if path]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base", default="HEAD^1", help="비교 기준. PR 병합 커밋의 첫 부모(대상 브랜치)")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    apps = {entry.name for entry in (root / "apps").iterdir() if (entry / "build.gradle").is_file()}
    try:
        paths = changed_paths(args.base) if args.event == "pull_request" else []
    except subprocess.CalledProcessError as error:
        sys.stderr.write("변경 목록을 얻지 못해 전체를 빌드한다: %s\n" % error)
        paths = []
    tasks = decide(args.event, paths, apps)
    sys.stderr.write("바뀐 파일 %d개 → %s\n" % (len(paths), " ".join(tasks) or "(Gradle 생략: 문서·Gradle이 읽지 않는 경로만 바뀜)"))
    print(output_line(tasks))


if __name__ == "__main__":
    main()
