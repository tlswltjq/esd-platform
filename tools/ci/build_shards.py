#!/usr/bin/env python3
"""결정 217: CI가 돌릴 Gradle 태스크(build_scope.py 출력)를 여러 러너로 나눌 matrix를 만든다.

- 빈 목록(문서·Gradle이 읽지 않는 경로만 바뀜): Gradle을 돌리지 않는다(``gradle=false``).
- 전체(``build``): 통합·이행 테스트를 ``INFRA_SHARDS``로 나누고, 첫 조각 ``unit``은 ``build``에서 그 작업만
  ``-x``로 뺀다. 배정되지 않은 통합 테스트(새 모듈)는 ``unit`` 조각에서 그대로 돈다 — 빠지지 않는다.
- 그 밖(앱 범위 빌드): 나누지 않고 한 조각으로 돌린다.

나눔은 2026-11-23 프로파일(``29a0d686``, 로컬 4코어)의 작업 시간으로 맞췄다. 조각 안에서도 동시 스택은
``stove.infraTest.maxParallel``(2)이다. 새 통합 테스트 모듈은 가장 짧은 조각에 더한다(``test_build_shards.py``가
``src/integrationTest``가 있는 모듈이 모두 배정됐는지 지킨다).

    python3 -I -B tools/ci/build_shards.py --tasks "build" >> "$GITHUB_OUTPUT"
"""
import argparse
import json
import sys

FULL = ['build']
# (조각 이름, 태스크). 괄호 안은 프로파일 작업 시간(초).
INFRA_SHARDS = (
    ('infra-payment', (
        ':apps:payment:integrationTest',      # 405
        ':apps:payment:migrationTest',        # 205
        ':apps:store:integrationTest',        # 99
        ':simulators:mock-pg:integrationTest',  # 23
    )),
    ('infra-order', (
        ':apps:order:integrationTest',        # 347
        ':apps:order:migrationTest',          # 171
        ':apps:license:integrationTest',      # 198
        ':apps:download:integrationTest',     # 21
    )),
    ('infra-catalog', (
        ':common:messaging-kafka:integrationTest',  # 156
        ':apps:catalog:integrationTest',      # 147
        ':apps:settlement:integrationTest',   # 134
        ':apps:studio:integrationTest',       # 113
        ':apps:auth:integrationTest',         # 83
        ':apps:web-bff:integrationTest',      # 63 (결정 223: BFF 세션 통합 테스트가 옮겨 옴)
    )),
    ('infra-messaging', (
        ':apps:review:integrationTest',       # 83
        ':common:messaging-rabbitmq:integrationTest',  # 79
        ':common:testcontainers:integrationTest',      # 77
        ':common:messaging-rabbitmq-streams:integrationTest',  # 73
        ':common:messaging-direct:integrationTest',    # 64
        ':common:messaging-redis:integrationTest',     # 63
        ':common:messaging:integrationTest',  # 49
    )),
)


def assigned():
    return [task for _, tasks in INFRA_SHARDS for task in tasks]


def plan(tasks):
    """build_scope.py의 태스크 목록 → matrix 항목 목록(``[]``이면 Gradle 없음)."""
    if not tasks:
        return []
    if tasks != FULL:
        return [{'shard': 'scoped', 'tasks': ' '.join(tasks)}]
    excluded = ' '.join('-x ' + task for task in assigned())
    shards = [{'shard': 'unit', 'tasks': 'build ' + excluded}]
    shards += [{'shard': name, 'tasks': ' '.join(tasks)} for name, tasks in INFRA_SHARDS]
    return shards


def output_lines(shards):
    return ['gradle=' + ('true' if shards else 'false'),
            'matrix=' + json.dumps({'include': shards}, ensure_ascii=False, separators=(',', ':'))]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--tasks', required=True, help='build_scope.py의 tasks 값(공백으로 나눔, 비면 Gradle 없음)')
    args = parser.parse_args(argv)
    shards = plan(args.tasks.split())
    sys.stderr.write('조각 %d개: %s\n' % (len(shards), ', '.join(s['shard'] for s in shards) or '(Gradle 없음)'))
    print('\n'.join(output_lines(shards)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
