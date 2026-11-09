"""결정 137: draft PR에서는 CI build를 건너뛰되, ready로 바꾸거나 push하면 반드시 다시 돈다.

job 수준 `if`로 건너뛴 작업은 필수 검사에서 성공으로 보고된다. 그래서 draft 건너뛰기만 두고
`ready_for_review`(또는 기본 이벤트 opened·synchronize·reopened)를 빠뜨리면 ready PR이 build 없이 병합될 수 있다.
PyYAML 없이 돌도록 ci.yml을 글자로 읽는다(review-gate Self-test는 표준 라이브러리만 쓴다).
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / '.github/workflows/ci.yml'
REQUIRED_TYPES = ('opened', 'synchronize', 'reopened', 'ready_for_review')
DRAFT_SKIP = 'github.event.pull_request.draft != true'


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


def draft_skip_problems(text):
    problems = []
    condition = job_condition(text, 'build')
    if condition is None or DRAFT_SKIP not in condition:
        problems.append('build 작업의 if에 `' + DRAFT_SKIP + '`가 없다')
    if condition is not None and "github.event_name != 'schedule'" not in condition:
        problems.append('build 작업이 schedule에서도 돈다')
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

    def test_ci_skips_drafts_and_runs_again_when_ready(self):
        self.assertEqual([], draft_skip_problems(self.text))

    def test_dropping_ready_for_review_is_caught(self):
        mutant = self.text.replace(', ready_for_review]', ']', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual(['pull_request types에 ready_for_review가 없다'], draft_skip_problems(mutant))

    def test_dropping_synchronize_is_caught(self):
        mutant = self.text.replace('synchronize, ', '', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual(['pull_request types에 synchronize가 없다'], draft_skip_problems(mutant))

    def test_dropping_draft_skip_is_caught(self):
        mutant = self.text.replace(' && ' + DRAFT_SKIP, '', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual(['build 작업의 if에 `' + DRAFT_SKIP + '`가 없다'], draft_skip_problems(mutant))


if __name__ == '__main__':
    unittest.main()
