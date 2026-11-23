"""결정 213: 측정·통합 검증 전후의 Docker 상태를 남기고 대조한다(AGENTS 5절 격리·보존).

    before  컨테이너·볼륨·네트워크를 상태 파일에 남긴다. 측정과 무관한 컨테이너가 실행 중이면
            목록을 내고 실패한다(S6를 kirothon-app-1이 켜진 채 재서 다시 잰 사례). 무관하지 않은
            이름은 `--allow`(앞부분 일치, 여러 번)로 준다. 확인하고 그대로 재려면 `--ignore-running`.
    after   상태 파일과 지금을 대조한다. 실행 전에 있던 것이 없어졌거나(보존 위반),
            새로 생긴 것이 남아 있으면(정리 누락) 목록을 내고 실패한다.

상태 파일 기본 위치는 저장소의 build/measure-guard/<이름>.json이다. 세션이 재시작돼도 남는다.
컨테이너를 멈추거나 지우지 않는다. 판단과 조치는 사람·에이전트가 한다.

    python3 tools/ci/measure_guard.py before --name s6 --allow messaging-measure-
    python3 tools/ci/measure_guard.py after --name s6

종료 코드: 0 문제 없음, 1 무관한 실행 중 컨테이너·보존 위반·정리 누락, 2 사용법 오류.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
NAME = re.compile(r'^[A-Za-z0-9._-]+$')
KINDS = ('containers', 'volumes', 'networks')
DEFAULT_NETWORKS = ('bridge', 'host', 'none')


def docker(args):
    result = subprocess.run(['docker'] + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise SystemExit('실패: docker ' + ' '.join(args) + '\n' + result.stderr.decode('utf-8', 'replace').strip())
    return [line for line in result.stdout.decode('utf-8').splitlines() if line.strip()]


def snapshot():
    containers = {}
    for line in docker(['ps', '-a', '--format', '{{.Names}}\t{{.State}}']):
        name, state = (line.split('\t') + [''])[:2]
        containers[name] = state
    return {
        'containers': containers,
        'volumes': sorted(docker(['volume', 'ls', '-q'])),
        'networks': sorted(name for name in docker(['network', 'ls', '--format', '{{.Name}}'])
                           if name not in DEFAULT_NETWORKS),
    }


def unrelated_running(state, allow):
    return sorted(name for name, status in state['containers'].items()
                  if status == 'running' and not any(name.startswith(prefix) for prefix in allow))


def compare(before, after):
    missing = {}
    leftover = {}
    for kind in KINDS:
        old = set(before[kind])
        new = set(after[kind])
        if old - new:
            missing[kind] = sorted(old - new)
        if new - old:
            leftover[kind] = sorted(new - old)
    return missing, leftover


def state_path(root, name):
    if not NAME.match(name):
        raise SystemExit('실패: --name은 영문·숫자·._-만 쓴다: ' + name)
    return Path(root) / 'build' / 'measure-guard' / (name + '.json')


def before(args):
    state = snapshot()
    path = state_path(args.root, args.name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding='utf-8')
    running = unrelated_running(state, args.allow)
    print('기록: {} (컨테이너 {} · 볼륨 {} · 네트워크 {})'.format(
        path, len(state['containers']), len(state['volumes']), len(state['networks'])))
    if running and not args.ignore_running:
        print('실패: 측정과 무관한 실행 중 컨테이너가 있다. 사용자와 확인해 멈추거나 --allow·--ignore-running을 준다:')
        for name in running:
            print('  - ' + name)
        return 1
    return 0


def after(args):
    path = state_path(args.root, args.name)
    if not path.exists():
        raise SystemExit('실패: 실행 전 기록이 없다: ' + str(path))
    missing, leftover = compare(json.loads(path.read_text(encoding='utf-8')), snapshot())
    for title, found in (('보존 위반(실행 전에 있었는데 없어짐)', missing), ('정리 누락(새로 생겨 남음)', leftover)):
        for kind, names in found.items():
            print('{} {}: {}'.format(title, kind, ', '.join(names)))
    if missing or leftover:
        return 1
    print('통과: 실행 전과 같다(컨테이너·볼륨·네트워크)')
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description='결정 213: 측정 전후 Docker 상태 대조')
    parser.add_argument('--root', default=str(ROOT))
    commands = parser.add_subparsers(dest='command', required=True)
    first = commands.add_parser('before')
    first.add_argument('--name', required=True)
    first.add_argument('--allow', action='append', default=[], help='무관하지 않은 컨테이너 이름 앞부분')
    first.add_argument('--ignore-running', action='store_true')
    second = commands.add_parser('after')
    second.add_argument('--name', required=True)
    args = parser.parse_args(argv)
    return before(args) if args.command == 'before' else after(args)


if __name__ == '__main__':
    sys.exit(main())
