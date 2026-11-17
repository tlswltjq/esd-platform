#!/usr/bin/env python3
"""PR CI가 빌드할 Gradle 태스크를 바뀐 파일로 정한다(결정 207).

규칙(보수적으로, 모르면 전부 빌드):
- PR이 아니면(main push·수동·예약) 항상 ``build``(전체). main은 매번 전체로 한 번 더 지킨다.
- 바뀐 파일이 전부 ``*.md``이면 Gradle을 돌리지 않는다(빈 목록).
- ``apps/<앱>/`` 아래만 바뀌었으면(``*.md`` 제외) 그 앱들만 ``:apps:<앱>:build``. 앱끼리는 프로젝트 의존이 없다.
- 그 밖의 경로(``common/``, 빌드 설정, ``simulators/``, ``infra/``, ``tools/``, ``.github/``, ``e2e/`` 등)가 하나라도 있으면 전체.
- 변경 목록이 비었으면(얻지 못했으면) 전체.

예: ``python3 -I -B tools/ci/build_scope.py --event pull_request --base HEAD^1 >> "$GITHUB_OUTPUT"``
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys

FULL = ["build"]
_APP = re.compile(r"^apps/([a-z][a-z-]*)/")


def decide(event, paths, apps):
    """``event``: GitHub 이벤트 이름. ``paths``: 바뀐 파일(저장소 기준). ``apps``: ``apps/`` 아래 앱 이름 집합."""
    if event != "pull_request" or not paths:
        return list(FULL)
    selected = set()
    for path in paths:
        if path.endswith(".md"):
            continue
        match = _APP.match(path)
        if match is None or match.group(1) not in apps:
            return list(FULL)
        selected.add(match.group(1))
    return [":apps:%s:build" % name for name in sorted(selected)]


def output_line(tasks):
    return "tasks=" + " ".join(tasks)


def changed_paths(base):
    output = subprocess.run(["git", "diff", "--name-only", base, "HEAD"], check=True,
                            capture_output=True, text=True).stdout
    return [line.strip() for line in output.splitlines() if line.strip()]


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
    sys.stderr.write("바뀐 파일 %d개 → %s\n" % (len(paths), " ".join(tasks) or "(Gradle 생략: 문서만 바뀜)"))
    print(output_line(tasks))


if __name__ == "__main__":
    main()
