"""결정 137: draft PR에서는 CI build를 건너뛰되, ready로 바꾸거나 push하면 반드시 다시 돈다.

job 수준 `if`로 건너뛴 작업은 필수 검사에서 성공으로 보고된다. 그래서 draft 건너뛰기만 두고
`ready_for_review`(또는 기본 이벤트 opened·synchronize·reopened)를 빠뜨리면 ready PR이 build 없이 병합될 수 있다.
draft 실행이 같은 concurrency 그룹에서 실제 build를 취소해도 같은 일이 생기므로 그룹을 나눈다.
조건식은 부분 문자열이 아니라 전체를 비교한다(`&& false`처럼 모든 build를 끄는 변경을 잡는다).
PyYAML 없이 돌도록 ci.yml을 글자로 읽는다(review-gate Self-test는 표준 라이브러리만 쓴다).
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / '.github/workflows/ci.yml'
REQUIRED_TYPES = ('opened', 'synchronize', 'reopened', 'ready_for_review')
BUILD_CONDITION = "github.event_name != 'schedule' && github.event.pull_request.draft != true"
CONCURRENCY_GROUP = "ci-${{ github.ref }}-${{ github.event.pull_request.draft == true && 'draft' || 'build' }}"


def pull_request_types(text):
    match = re.search(r'^  pull_request:\n    types: \[([^\]\n]*)\]\n', text, re.M)
    if match is None:
        return None
    return [name.strip() for name in match.group(1).split(',')]


def job_condition(text, job):
    match = re.search(r'^  ' + re.escape(job) + r':\n((?:    .*\n)+)', text, re.M)
    if match is None:
        return None
    condition = re.search(r'^    if: (.+)$', match.group(1), re.M)
    return None if condition is None else condition.group(1)


def concurrency_group(text):
    match = re.search(r'^concurrency:\n  group: (.+)$', text, re.M)
    return None if match is None else match.group(1)


def draft_skip_problems(text):
    problems = []
    if job_condition(text, 'build') != BUILD_CONDITION:
        problems.append('build 작업의 if가 `' + BUILD_CONDITION + '`가 아니다')
    if concurrency_group(text) != CONCURRENCY_GROUP:
        problems.append('concurrency 그룹이 draft 실행과 실제 build 실행을 나누지 않는다')
    types = pull_request_types(text)
    if types is None:
        problems.append('pull_request에 types 목록이 없다')
    else:
        for name in REQUIRED_TYPES:
            if name not in types:
                problems.append('pull_request types에 ' + name + '가 없다')
    return problems


class DraftSkipContractTest(unittest.TestCase):
    def setUp(self):
        self.text = CI.read_text(encoding='utf-8')

    def mutate(self, old, new):
        mutant = self.text.replace(old, new, 1)
        self.assertNotEqual(self.text, mutant)
        return draft_skip_problems(mutant)

    def test_ci_skips_drafts_and_runs_again_when_ready(self):
        self.assertEqual([], draft_skip_problems(self.text))

    def test_dropping_ready_for_review_is_caught(self):
        self.assertEqual(['pull_request types에 ready_for_review가 없다'],
                         self.mutate(', ready_for_review]', ']'))

    def test_dropping_synchronize_is_caught(self):
        self.assertEqual(['pull_request types에 synchronize가 없다'], self.mutate('synchronize, ', ''))

    def test_dropping_draft_skip_is_caught(self):
        self.assertEqual(['build 작업의 if가 `' + BUILD_CONDITION + '`가 아니다'],
                         self.mutate(' && github.event.pull_request.draft != true', ''))

    def test_disabling_every_build_is_caught(self):
        self.assertEqual(['build 작업의 if가 `' + BUILD_CONDITION + '`가 아니다'],
                         self.mutate('draft != true\n', 'draft != true && false\n'))

    def test_shared_concurrency_group_is_caught(self):
        self.assertEqual(['concurrency 그룹이 draft 실행과 실제 build 실행을 나누지 않는다'],
                         self.mutate(CONCURRENCY_GROUP, 'ci-${{ github.ref }}'))


if __name__ == '__main__':
    unittest.main()
