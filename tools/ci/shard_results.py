#!/usr/bin/env python3
"""결정 217(PR #234 R1): 필수 검사 build가 plan과 gradle 조각의 실제 결과를 하나씩 확인한다.

matrix 작업의 합친 결과(`needs.gradle.result`)는 일부 조각만 다시 돌렸거나 조각이 러너를 받지 못해 취소됐을 때
`success`로 보고된 사례가 있다. 그래서 이 실행의 작업 목록(GitHub API `jobs?filter=latest` — 다시 돌리지 않은
작업은 이전 시도의 결과)을 읽어, plan이 정한 조각마다 `gradle (<조각>)` 작업이 정확히 하나 있고 그 결론이
`success`인지 본다.

    python3 -I -B tools/ci/shard_results.py --plan success --gradle true --matrix "$MATRIX" --jobs jobs.tsv
"""
import argparse
import json
import sys
from pathlib import Path

SHARD_JOB = 'gradle ({})'


def read_jobs(text):
    """`이름<TAB>결론` 줄 → [(이름, 결론)]. 결론이 없으면(진행 중) 상태 값이 온다."""
    jobs = []
    for line in text.splitlines():
        if not line.strip():
            continue
        name, _, conclusion = line.partition('\t')
        jobs.append((name.strip(), conclusion.strip()))
    return jobs


def problems(plan, gradle, matrix, jobs):
    if plan != 'success':
        return ['plan이 성공하지 않았다: ' + (plan or '(없음)')]
    if gradle not in ('true', 'false'):
        return ['plan의 gradle 출력이 true·false가 아니다: ' + (gradle or '(없음)')]
    try:
        shards = [item['shard'] for item in json.loads(matrix or '{}').get('include', [])]
    except (ValueError, TypeError, KeyError) as error:
        return ['plan의 matrix를 읽지 못했다: ' + str(error)]
    if gradle == 'false':
        return [] if not shards else ['Gradle을 생략한다면서 조각이 있다: ' + ', '.join(shards)]
    if not shards:
        return ['Gradle을 돌린다면서 조각이 없다']
    found = []
    for shard in shards:
        name = SHARD_JOB.format(shard)
        results = [conclusion for job, conclusion in jobs if job == name]
        if len(results) != 1:
            found.append('`{}` 작업이 {}개다(기대 1개)'.format(name, len(results)))
        elif results[0] != 'success':
            found.append('`{}`의 결론이 {}다'.format(name, results[0] or '(없음)'))
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--plan', required=True, help='needs.plan.result')
    parser.add_argument('--gradle', required=True, help='needs.plan.outputs.gradle')
    parser.add_argument('--matrix', required=True, help='needs.plan.outputs.matrix')
    parser.add_argument('--jobs', required=True, help='이 실행의 작업 목록 파일(`이름<TAB>결론`)')
    args = parser.parse_args(argv)
    jobs = read_jobs(Path(args.jobs).read_text(encoding='utf-8'))
    found = problems(args.plan, args.gradle, args.matrix, jobs)
    if found:
        print('실패: build 결과 모음(결정 217)')
        for item in found:
            print('- ' + item)
        return 1
    print('통과: plan 성공, Gradle ' + ('생략' if args.gradle == 'false' else '조각 모두 성공'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
