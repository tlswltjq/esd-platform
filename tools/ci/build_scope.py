#!/usr/bin/env python3
"""PR CI가 빌드할 Gradle 태스크를 바뀐 파일로 정한다(결정 207).

규칙(보수적으로, 모르면 전부 빌드):
- PR이 아니면(main push·수동·예약) 항상 ``build``(전체). main은 매번 전체로 한 번 더 지킨다.
- 바뀐 파일이 전부 ``*.md``이거나 Gradle이 읽지 않는 경로(``NOT_READ_BY_GRADLE``, 결정 216)이면 Gradle을 돌리지 않는다(빈 목록).
  그 경로의 Python 테스트는 build 작업의 별도 단계가 늘 돌린다. 목록은 허용 목록이다 — 모르는 경로는 전체다.
- ``apps/<앱>/`` 아래만 바뀌었으면(``*.md`` 제외) 그 앱들의 ``:apps:<앱>:build``와 ``:common:test:test``.
  앱끼리는 프로젝트 의존이 없지만, ``common:test``의 배포 계약 테스트가 모든 앱의 ``application.yml``을 읽는다
  (``common/test/build.gradle``의 ``inputs.files``, PR #208 R1).
- 이름 바꾸기는 옛 경로와 새 경로를 둘 다 본다(``--no-renames``, PR #208 R2).
- 그 밖의 경로(``common/``, 빌드 설정, ``infra/``, ``tools/``, ``.github/``, ``e2e/`` 등)가 하나라도 있으면 전체.
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
NOT_READ_BY_GRADLE_PREFIXES = ("tools/ci/", "tools/measurement/", "tools/verification/", ".claude/")
NOT_READ_BY_GRADLE_FILES = (".gitignore",)


def not_read_by_gradle(path):
    if path.endswith(".gradle"):
        return False
    return path in NOT_READ_BY_GRADLE_FILES or path.startswith(NOT_READ_BY_GRADLE_PREFIXES)


def decide(event, paths, apps):
    """``event``: GitHub 이벤트 이름. ``paths``: 바뀐 파일(저장소 기준). ``apps``: ``apps/`` 아래 앱 이름 집합."""
    if event != "pull_request" or not paths:
        return list(FULL)
    selected = set()
    for path in paths:
        if path.endswith(".md") or not_read_by_gradle(path):
            continue
        match = _APP.match(path)
        if match is None or match.group(1) not in apps:
            return list(FULL)
        selected.add(match.group(1))
    if not selected:
        return []
    return [":apps:%s:build" % name for name in sorted(selected)] + [APP_FILE_READERS]


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
