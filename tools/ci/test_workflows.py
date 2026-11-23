"""결정 137: draft PR에서는 CI build를 건너뛰되, ready로 바꾸거나 push하면 반드시 다시 돈다.

job 수준 `if`로 건너뛴 작업은 필수 검사에서 성공으로 보고된다. 그래서 draft 건너뛰기만 두고
`ready_for_review`(또는 기본 이벤트 opened·synchronize·reopened)를 빠뜨리면 ready PR이 build 없이 병합될 수 있다.
draft 실행이 같은 concurrency 그룹에서 실제 build를 취소해도 같은 일이 생기므로 그룹을 나눈다.
밤·수동 스택 실행도 main push와 그룹을 나눈다(PR #232 R5: 서로 기다리거나 대기 실행이 밀려 사라진다).
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
CONCURRENCY_GROUP = "ci-${{ github.ref }}-${{ github.event.pull_request.draft == true && 'draft' || (github.event_name == 'schedule' || github.event_name == 'workflow_dispatch') && 'stack' || 'build' }}"


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
        problems.append('concurrency 그룹이 draft·스택(밤·수동)·build 실행을 나누지 않는다')
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
        self.assertEqual(['concurrency 그룹이 draft·스택(밤·수동)·build 실행을 나누지 않는다'],
                         self.mutate(CONCURRENCY_GROUP, 'ci-${{ github.ref }}'))

    def test_r5_a_stack_run_sharing_the_main_push_group_is_caught(self):
        shared = "ci-${{ github.ref }}-${{ github.event.pull_request.draft == true && 'draft' || 'build' }}"
        self.assertEqual(['concurrency 그룹이 draft·스택(밤·수동)·build 실행을 나누지 않는다'],
                         self.mutate(CONCURRENCY_GROUP, shared))


class BuildScopeWiringTest(unittest.TestCase):
    """결정 207: 범위 판단 단계(id scope)의 출력으로만 Gradle 태스크를 고른다. 연결이 끊기면 잡는다(PR #208 R3)."""

    PRODUCER = ('      - name: Build scope\n'
                '        id: scope\n'
                '        run: python3 -I -B tools/ci/build_scope.py --event "${{ github.event_name }}" --base HEAD^1'
                ' >> "$GITHUB_OUTPUT"\n')
    CONSUMER = ("      - name: Build\n"
                "        if: steps.scope.outputs.tasks != ''\n"
                "        run: ./gradlew ${{ steps.scope.outputs.tasks }} --continue --console=plain\n")

    def setUp(self):
        self.text = CI.read_text(encoding='utf-8')

    def problems(self, text):
        build = re.search(r'^  build:\n((?:(?:    .*)?\n)+)', text, re.M)
        body = '' if build is None else build.group(1)
        found = []
        if self.PRODUCER not in body:
            found.append('build 작업에 id scope 범위 판단 단계가 없다')
        if self.CONSUMER not in body:
            found.append('Build 단계가 steps.scope.outputs.tasks 로 돌지 않는다')
        elif body.index(self.CONSUMER) < body.index(self.PRODUCER) if self.PRODUCER in body else False:
            found.append('Build 단계가 범위 판단보다 앞에 있다')
        if 'run: ./gradlew build --continue --console=plain' in body:
            found.append('무조건 전체 빌드가 남아 있다')
        return found

    def test_build_runs_the_tasks_chosen_by_the_scope_step(self):
        self.assertEqual([], self.problems(self.text))

    def test_removing_or_renaming_the_scope_step_id_is_caught(self):
        for replacement in ['', '        id: range\n']:
            mutant = self.text.replace('        id: scope\n', replacement, 1)
            self.assertNotEqual(self.text, mutant)
            self.assertIn('build 작업에 id scope 범위 판단 단계가 없다', self.problems(mutant))

    def test_going_back_to_an_unconditional_full_build_is_caught(self):
        mutant = self.text.replace(self.CONSUMER, '      - name: Build\n        run: ./gradlew build --continue --console=plain\n', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertIn('Build 단계가 steps.scope.outputs.tasks 로 돌지 않는다', self.problems(mutant))



class PythonToolTestsWiringTest(unittest.TestCase):
    """결정 216: Gradle이 읽지 않는 도구 경로만 바뀐 PR은 Gradle을 생략한다. 그 Python 테스트는 build 작업이 늘 돌려야 한다."""

    NAME = "      - name: Python tool tests\n"
    COMMANDS = ("python3 -B -m unittest discover -s tools/verification -p 'test_*.py'",
                "python3 -B -m unittest discover -s tools/measurement -p 'test_*.py'")

    def setUp(self):
        self.text = CI.read_text(encoding='utf-8')

    def step(self, text):
        """build 작업 안의 그 단계 전체(다음 단계 또는 작업 끝까지). 없으면 None."""
        build = re.search(r'^  build:\n((?:(?:    .*)?\n)+)', text, re.M)
        body = '' if build is None else build.group(1)
        if self.NAME not in body:
            return None
        rest = body[body.index(self.NAME) + len(self.NAME):]
        following = re.search(r'^      - ', rest, re.M)
        return rest if following is None else rest[:following.start()]

    def problems(self, text):
        step = self.step(text)
        if step is None:
            return ['build 작업에 Python 도구 테스트 단계가 없다']
        found = [command + ' 가 없다' for command in self.COMMANDS if command not in step]
        if re.search(r'^        if:', step, re.M):
            found.append('Python 도구 테스트 단계에 조건(if)이 붙었다')
        return found

    def test_build_always_runs_the_python_tool_tests(self):
        self.assertEqual([], self.problems(self.text))

    def test_a_condition_before_or_after_run_is_caught(self):
        # PR #230 R2: 명령 뒤에 붙은 조건도 잡는다.
        before = self.text.replace(self.NAME + "        run: |\n",
                                   self.NAME + "        if: steps.scope.outputs.tasks != ''\n        run: |\n", 1)
        after = self.text.replace(self.COMMANDS[1] + "\n", self.COMMANDS[1] + "\n        if: false\n", 1)
        for mutant in (before, after):
            self.assertNotEqual(self.text, mutant)
            self.assertIn('Python 도구 테스트 단계에 조건(if)이 붙었다', self.problems(mutant))

    def test_dropping_a_tool_directory_is_caught(self):
        mutant = self.text.replace("          " + self.COMMANDS[1] + "\n", "", 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual([self.COMMANDS[1] + ' 가 없다'], self.problems(mutant))


class BuildStepOrderTest(unittest.TestCase):
    """PR #233 R6·R7: 번호 검사 → 범위 판단 → (Gradle을 돌릴 때만) Java·Gradle 준비 → Build 순서를 지킨다."""

    STEPS = {
        'numbers': ('      - name: Check decision numbers\n'
                    '        if: ${{ !cancelled() }}\n'
                    '        run: python3 -I -B tools/ci/decision_numbers.py --root . --base-ref HEAD^1\n'),
        'scope': BuildScopeWiringTest.PRODUCER,
        'java': "      - uses: actions/setup-java@v4\n        if: steps.scope.outputs.tasks != ''\n",
        'gradle': "      - uses: gradle/actions/setup-gradle@v4\n        if: steps.scope.outputs.tasks != ''\n",
        'build': BuildScopeWiringTest.CONSUMER,
    }
    ORDER = ('numbers', 'scope', 'java', 'gradle', 'build')

    def setUp(self):
        self.text = CI.read_text(encoding='utf-8')

    def problems(self, text):
        build = re.search(r'^  build:\n((?:(?:    .*)?\n)+)', text, re.M)
        body = '' if build is None else build.group(1)
        found = [name + ' 단계가 없거나 형식이 다르다' for name, step in self.STEPS.items() if step not in body]
        if not found:
            positions = [body.index(self.STEPS[name]) for name in self.ORDER]
            if positions != sorted(positions):
                found.append('단계 순서가 ' + ' → '.join(self.ORDER) + '가 아니다')
            if body.index('      - uses: actions/checkout@v4\n') > positions[0]:
                found.append('checkout보다 앞에 단계가 있다')
        if 'docker version' in body:
            found.append('Docker 버전 출력 단계가 남아 있다')
        return found

    def test_numbers_and_scope_run_before_java_and_gradle(self):
        self.assertEqual([], self.problems(self.text))

    def test_numbers_after_build_is_caught(self):
        numbers = self.STEPS['numbers']
        mutant = self.text.replace(numbers, '', 1).replace(self.STEPS['build'], self.STEPS['build'] + numbers, 1)
        self.assertNotEqual(self.text, mutant)
        self.assertIn('단계 순서가 ' + ' → '.join(self.ORDER) + '가 아니다', self.problems(mutant))

    def test_unconditional_java_setup_is_caught(self):
        mutant = self.text.replace(self.STEPS['java'], '      - uses: actions/setup-java@v4\n', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual(['java 단계가 없거나 형식이 다르다'], self.problems(mutant))

    def test_docker_version_step_is_caught(self):
        mutant = self.text.replace(self.STEPS['scope'], self.STEPS['scope'] + "      - name: Docker\n        run: docker version\n", 1)
        self.assertEqual(['Docker 버전 출력 단계가 남아 있다'], self.problems(mutant))


if __name__ == '__main__':
    unittest.main()
