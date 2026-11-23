"""결정 213: Gradle 테스트를 돌리고 이번 실행이 만든 결과로만 통과를 판정한다.

실행 전에 저장소 아래 모든 `build/test-results`를 지운다(하위 저장소·worktree는 제외). Gradle 명령에는
`--no-build-cache`를 붙여 캐시 복원을 실행으로 읽지 않는다. 그다음 Gradle을 돌리고, 종료 코드가 0이고
이번에 새로 생긴 JUnit XML이 하나 이상이며 실패·오류가 0일 때만 통과다. 컴파일 실패로 테스트가 돌지
않으면 XML이 없으므로 통과가 아니다(PR #215에서 이전 XML을 통과로 읽은 사례).
`--repeat N`은 같은 명령을 N회 돌리고 회차마다 위 판정을 한다. 한 회차라도 실패하면 거기서 멈춘다.
Gradle 출력은 `--log`(기본 build/gradle_result.log)에 남기고 화면에는 요약만 낸다.

    python3 tools/ci/gradle_result.py --repeat 3 -- ./gradlew :apps:order:test --tests '*Refund*'

종료 코드: 0 통과, 1 실패(테스트 실패·Gradle 실패·XML 없음), 2 사용법 오류.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[2]
PRUNE = {'.git', '.gradle', 'node_modules', '.idea', '.claude'}
NO_CACHE = '--no-build-cache'
TAIL_LINES = 30


def nested_checkout(path):
    # 하위 저장소·worktree(.git 파일 또는 폴더)는 다른 작업의 결과라 건드리지 않는다(PR #226 R5).
    return (path / '.git').exists()


def result_dirs(root):
    found = []
    for current, dirs, _ in os.walk(str(root)):
        dirs[:] = [name for name in dirs if name not in PRUNE and not nested_checkout(Path(current) / name)]
        if Path(current).name == 'build' and 'test-results' in dirs:
            found.append(Path(current) / 'test-results')
            dirs.remove('test-results')
    return found


def clear_results(root):
    removed = result_dirs(root)
    for path in removed:
        # build/test-results만 지운다. 경로가 저장소 밖이면 멈춘다.
        if Path(root).resolve() not in path.resolve().parents:
            raise SystemExit('실패: 저장소 밖 경로는 지우지 않는다: ' + str(path))
        shutil.rmtree(str(path))
    return len(removed)


def summarize(root, since):
    totals = {'files': 0, 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0, 'failed': []}
    for directory in result_dirs(root):
        for path in sorted(directory.rglob('TEST-*.xml')):
            if path.stat().st_mtime < since:
                continue
            suite = ElementTree.parse(str(path)).getroot()
            totals['files'] += 1
            for key in ('tests', 'failures', 'errors', 'skipped'):
                totals[key] += int(suite.get(key, '0'))
            for case in suite.iter('testcase'):
                if case.find('failure') is not None or case.find('error') is not None:
                    totals['failed'].append(case.get('classname', '') + '#' + case.get('name', ''))
    return totals


def verdict(exit_code, totals):
    if exit_code != 0:
        return False, 'Gradle 종료 코드 ' + str(exit_code)
    if totals['files'] == 0:
        return False, '이번 실행이 만든 JUnit XML이 없다(테스트가 돌지 않았다)'
    if totals['failures'] or totals['errors']:
        return False, '실패 {} · 오류 {}'.format(totals['failures'], totals['errors'])
    return True, '테스트 {}개 통과(건너뜀 {}, XML {}개)'.format(totals['tests'], totals['skipped'], totals['files'])


def tail(path, count=TAIL_LINES):
    lines = Path(path).read_text(encoding='utf-8', errors='replace').splitlines()
    return '\n'.join(lines[-count:])


def without_cache(command):
    """Gradle 명령이면 빌드 캐시를 끈다. 캐시에서 복원된 XML도 새 mtime을 가져 실행으로 보이기 때문이다
    (gradle.properties의 org.gradle.caching=true, PR #226 R1). 결과 폴더를 지웠으니 up-to-date로도 건너뛰지 않는다."""
    if Path(command[0]).name in ('gradlew', 'gradle') and NO_CACHE not in command:
        return command + [NO_CACHE]
    return command


def run_once(root, command, log):
    clear_results(root)
    started = time.time() - 1
    with open(str(log), 'w', encoding='utf-8') as handle:
        exit_code = subprocess.run(command, cwd=str(root), stdout=handle, stderr=subprocess.STDOUT).returncode
    totals = summarize(root, started)
    return exit_code, totals


def main(argv=None):
    parser = argparse.ArgumentParser(description='결정 213: Gradle 테스트를 이번 실행 결과로만 판정')
    parser.add_argument('--root', default=str(ROOT))
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--log', help='Gradle 출력 파일(기본: <root>/build/gradle_result.log)')
    parser.add_argument('command', nargs=argparse.REMAINDER, help='-- 뒤에 Gradle 명령')
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command or args.repeat < 1:
        parser.error('-- 뒤에 실행할 Gradle 명령을 주고, --repeat는 1 이상이어야 한다')
    command = without_cache(command)
    root = Path(args.root)
    log = Path(args.log) if args.log else root / 'build' / 'gradle_result.log'
    log.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, args.repeat + 1):
        exit_code, totals = run_once(root, command, log)
        passed, reason = verdict(exit_code, totals)
        print('{}/{}회: {} — {}'.format(attempt, args.repeat, '통과' if passed else '실패', reason))
        if not passed:
            for name in totals['failed'][:20]:
                print('  - ' + name)
            print('--- Gradle 출력 끝 {}줄 ({}) ---'.format(TAIL_LINES, log))
            print(tail(log))
            return 1
    print('통과: {}회 모두'.format(args.repeat))
    return 0


if __name__ == '__main__':
    sys.exit(main())
