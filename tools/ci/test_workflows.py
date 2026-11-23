"""결정 137: draft PR에서는 CI build를 건너뛰되, ready로 바꾸거나 push하면 반드시 다시 돈다.

job 수준 `if`로 건너뛴 작업은 필수 검사에서 성공으로 보고된다. 그래서 draft 건너뛰기만 두고
`ready_for_review`(또는 기본 이벤트 opened·synchronize·reopened)를 빠뜨리면 ready PR이 build 없이 병합될 수 있다.
draft 실행이 같은 concurrency 그룹에서 실제 build를 취소해도 같은 일이 생기므로 그룹을 나눈다.
밤·수동 스택 실행도 main push와 그룹을 나눈다(PR #232 R5: 서로 기다리거나 대기 실행이 밀려 사라진다).
결정 217: build는 plan → gradle(matrix) → build(결과 모음)로 나뉜다. 결과를 모으는 build는 always()로 돌아야 한다 —
조각이 실패해 build가 건너뛰어지면 필수 검사가 성공으로 보고되기 때문이다.
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
AGGREGATE_CONDITION = 'always() && ' + BUILD_CONDITION
CONCURRENCY_GROUP = "ci-${{ github.ref }}-${{ github.event.pull_request.draft == true && 'draft' || (github.event_name == 'schedule' || github.event_name == 'workflow_dispatch') && 'stack' || 'build' }}"
REQUIRED_NAME = 'build (unit · architecture · integration · migration)'


def pull_request_types(text):
    match = re.search(r'^  pull_request:\n    types: \[([^\]\n]*)\]\n', text, re.M)
    if match is None:
        return None
    return [name.strip() for name in match.group(1).split(',')]


def job_body(text, job):
    """작업 하나의 본문(빈 줄·더 깊은 들여쓰기 포함, 다음 작업 전까지). 없으면 ''."""
    match = re.search(r'^  ' + re.escape(job) + r':\n((?:(?:    .*)?\n)+)', text, re.M)
    return '' if match is None else match.group(1)


def job_condition(text, job):
    condition = re.search(r'^    if: (.+)$', job_body(text, job), re.M)
    return None if condition is None else condition.group(1)


def concurrency_group(text):
    match = re.search(r'^concurrency:\n  group: (.+)$', text, re.M)
    return None if match is None else match.group(1)


def draft_skip_problems(text):
    problems = []
    if job_condition(text, 'plan') != BUILD_CONDITION:
        problems.append('plan 작업의 if가 `' + BUILD_CONDITION + '`가 아니다')
    if job_condition(text, 'build') != AGGREGATE_CONDITION:
        problems.append('build 작업의 if가 `' + AGGREGATE_CONDITION + '`가 아니다')
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
        self.assertEqual(['plan 작업의 if가 `' + BUILD_CONDITION + '`가 아니다'],
                         self.mutate(' && github.event.pull_request.draft != true', ''))

    def test_disabling_every_build_is_caught(self):
        self.assertEqual(['plan 작업의 if가 `' + BUILD_CONDITION + '`가 아니다'],
                         self.mutate('draft != true\n', 'draft != true && false\n'))

    def test_aggregate_without_always_is_caught(self):
        # 결정 217: always()가 없으면 조각 실패 때 build가 건너뛰어지고, 건너뛴 필수 검사는 성공으로 보고된다.
        self.assertEqual(['build 작업의 if가 `' + AGGREGATE_CONDITION + '`가 아니다'],
                         self.mutate('    if: ' + AGGREGATE_CONDITION + '\n', '    if: ' + BUILD_CONDITION + '\n'))

    def test_shared_concurrency_group_is_caught(self):
        self.assertEqual(['concurrency 그룹이 draft·스택(밤·수동)·build 실행을 나누지 않는다'],
                         self.mutate(CONCURRENCY_GROUP, 'ci-${{ github.ref }}'))

    def test_r5_a_stack_run_sharing_the_main_push_group_is_caught(self):
        shared = "ci-${{ github.ref }}-${{ github.event.pull_request.draft == true && 'draft' || 'build' }}"
        self.assertEqual(['concurrency 그룹이 draft·스택(밤·수동)·build 실행을 나누지 않는다'],
                         self.mutate(CONCURRENCY_GROUP, shared))


class BuildScopeWiringTest(unittest.TestCase):
    """결정 207·217: 범위 판단(id scope) → 나눔(id shards) → plan 출력 → gradle matrix로만 Gradle 태스크를 고른다."""

    PRODUCER = ('      - name: Build scope\n'
                '        id: scope\n'
                '        run: python3 -I -B tools/ci/build_scope.py --event "${{ github.event_name }}" --base HEAD^1'
                ' >> "$GITHUB_OUTPUT"\n')
    SHARDS = ('      - name: Build shards\n'
              '        id: shards\n'
              '        run: python3 -I -B tools/ci/build_shards.py --tasks "${{ steps.scope.outputs.tasks }}"'
              ' >> "$GITHUB_OUTPUT"\n')
    OUTPUTS = ('    outputs:\n'
               '      gradle: ${{ steps.shards.outputs.gradle }}\n'
               '      matrix: ${{ steps.shards.outputs.matrix }}\n')
    GRADLE_IF = "    if: needs.plan.outputs.gradle == 'true'\n"
    MATRIX = '      matrix: ${{ fromJSON(needs.plan.outputs.matrix) }}\n'
    CONSUMER = ('      - name: Build\n'
                '        run: ./gradlew ${{ matrix.tasks }} --continue --console=plain\n')

    def setUp(self):
        self.text = CI.read_text(encoding='utf-8')

    def problems(self, text):
        plan = job_body(text, 'plan')
        gradle = job_body(text, 'gradle')
        found = []
        if self.PRODUCER not in plan:
            found.append('plan 작업에 id scope 범위 판단 단계가 없다')
        if self.SHARDS not in plan:
            found.append('plan 작업에 범위를 받는 id shards 나눔 단계가 없다')
        elif self.PRODUCER in plan and plan.index(self.SHARDS) < plan.index(self.PRODUCER):
            found.append('나눔 단계가 범위 판단보다 앞에 있다')
        if self.OUTPUTS not in plan:
            found.append('plan이 나눔 결과를 출력하지 않는다')
        for needed, message in ((self.GRADLE_IF, 'gradle 작업이 plan의 gradle 출력으로 돌지 않는다'),
                                (self.MATRIX, 'gradle matrix가 plan의 matrix 출력이 아니다'),
                                (self.CONSUMER, 'gradle Build 단계가 matrix.tasks 로 돌지 않는다')):
            if needed not in gradle:
                found.append(message)
        if 'run: ./gradlew build --continue --console=plain' in text:
            found.append('무조건 전체 빌드가 남아 있다')
        return found

    def test_build_runs_the_tasks_chosen_by_the_scope_step(self):
        self.assertEqual([], self.problems(self.text))

    def test_removing_or_renaming_the_scope_step_id_is_caught(self):
        for replacement in ['', '        id: range\n']:
            mutant = self.text.replace('        id: scope\n', replacement, 1)
            self.assertNotEqual(self.text, mutant)
            self.assertIn('plan 작업에 id scope 범위 판단 단계가 없다', self.problems(mutant))

    def test_going_back_to_an_unconditional_full_build_is_caught(self):
        mutant = self.text.replace(self.CONSUMER, '      - name: Build\n        run: ./gradlew build --continue --console=plain\n', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertIn('gradle Build 단계가 matrix.tasks 로 돌지 않는다', self.problems(mutant))
        self.assertIn('무조건 전체 빌드가 남아 있다', self.problems(mutant))

    def test_a_matrix_not_from_the_plan_is_caught(self):
        mutant = self.text.replace(self.MATRIX, "      matrix:\n        shard: [all]\n", 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual(['gradle matrix가 plan의 matrix 출력이 아니다'], self.problems(mutant))


class PythonToolTestsWiringTest(unittest.TestCase):
    """결정 216: Gradle이 읽지 않는 도구 경로만 바뀐 PR은 Gradle을 생략한다. 그 Python 테스트는 plan 작업이 늘 돌려야 한다."""

    NAME = "      - name: Python tool tests\n"
    COMMANDS = ("python3 -B -m unittest discover -s tools/verification -p 'test_*.py'",
                "python3 -B -m unittest discover -s tools/measurement -p 'test_*.py'")

    def setUp(self):
        self.text = CI.read_text(encoding='utf-8')

    def step(self, text):
        """plan 작업 안의 그 단계 전체(다음 단계 또는 작업 끝까지). 없으면 None."""
        body = job_body(text, 'plan')
        if self.NAME not in body:
            return None
        rest = body[body.index(self.NAME) + len(self.NAME):]
        following = re.search(r'^      - ', rest, re.M)
        return rest if following is None else rest[:following.start()]

    def problems(self, text):
        step = self.step(text)
        if step is None:
            return ['plan 작업에 Python 도구 테스트 단계가 없다']
        found = [command + ' 가 없다' for command in self.COMMANDS if command not in step]
        if re.search(r'^        if:', step, re.M):
            found.append('Python 도구 테스트 단계에 조건(if)이 붙었다')
        return found

    def test_plan_always_runs_the_python_tool_tests(self):
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


class PlanStepOrderTest(unittest.TestCase):
    """PR #233 R6·R7·결정 217: plan은 Java·Gradle 없이 번호 검사 → 범위 판단 → 나눔 순서로 돈다. Docker 버전 단계는 없다."""

    STEPS = {
        'numbers': ('      - name: Check decision numbers\n'
                    '        if: ${{ !cancelled() }}\n'
                    '        run: python3 -I -B tools/ci/decision_numbers.py --root . --base-ref HEAD^1\n'),
        'scope': BuildScopeWiringTest.PRODUCER,
        'shards': BuildScopeWiringTest.SHARDS,
    }
    ORDER = ('numbers', 'scope', 'shards')

    def setUp(self):
        self.text = CI.read_text(encoding='utf-8')

    def problems(self, text):
        plan = job_body(text, 'plan')
        found = [name + ' 단계가 없거나 형식이 다르다' for name, step in self.STEPS.items() if step not in plan]
        if not found:
            positions = [plan.index(self.STEPS[name]) for name in self.ORDER]
            if positions != sorted(positions):
                found.append('단계 순서가 ' + ' → '.join(self.ORDER) + '가 아니다')
            if plan.index('      - uses: actions/checkout@v4\n') > positions[0]:
                found.append('checkout보다 앞에 단계가 있다')
        if 'setup-java' in plan or 'setup-gradle' in plan:
            found.append('plan이 Java·Gradle을 준비한다(Gradle을 생략하는 PR에도 비용이 든다)')
        if 'docker version' in text:
            found.append('Docker 버전 출력 단계가 남아 있다')
        return found

    def test_numbers_and_scope_run_without_java(self):
        self.assertEqual([], self.problems(self.text))

    def test_numbers_after_shards_is_caught(self):
        numbers = self.STEPS['numbers']
        mutant = self.text.replace(numbers, '', 1).replace(self.STEPS['shards'], self.STEPS['shards'] + numbers, 1)
        self.assertNotEqual(self.text, mutant)
        self.assertIn('단계 순서가 ' + ' → '.join(self.ORDER) + '가 아니다', self.problems(mutant))

    def test_java_setup_in_plan_is_caught(self):
        mutant = self.text.replace(self.STEPS['scope'], self.STEPS['scope'] + '      - uses: actions/setup-java@v4\n', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual(['plan이 Java·Gradle을 준비한다(Gradle을 생략하는 PR에도 비용이 든다)'], self.problems(mutant))

    def test_docker_version_step_is_caught(self):
        mutant = self.text.replace(self.STEPS['scope'], self.STEPS['scope'] + "      - name: Docker\n        run: docker version\n", 1)
        self.assertEqual(['Docker 버전 출력 단계가 남아 있다'], self.problems(mutant))


class AggregateResultTest(unittest.TestCase):
    """결정 217·107: 필수 검사 이름은 결과를 모으는 build가 갖고, plan·gradle 결과를 모두 본다."""

    def setUp(self):
        self.text = CI.read_text(encoding='utf-8')

    def problems(self, text):
        build = job_body(text, 'build')
        found = []
        if '    name: ' + REQUIRED_NAME + '\n' not in build:
            found.append('build 작업 이름이 필수 검사 이름이 아니다')
        if '    needs: [plan, gradle]\n' not in build:
            found.append('build가 plan·gradle을 기다리지 않는다')
        for needed in ('PLAN: ${{ needs.plan.result }}', 'GRADLE: ${{ needs.gradle.result }}',
                       'RUN_GRADLE: ${{ needs.plan.outputs.gradle }}',
                       'if [ "$PLAN" != success ]', 'if [ "$GRADLE" != success ]', '[ "$GRADLE" != skipped ]'):
            if needed not in build:
                found.append('결과 모음에 `' + needed + '`가 없다')
        for job in ('plan', 'gradle'):
            name = re.search(r'^    name: (.+)$', job_body(text, job), re.M)
            if name is None or name.group(1).startswith('build'):
                # tools/ci/pr_finish.py는 `build` 접두로 필수 검사를 찾는다.
                found.append(job + ' 작업 이름이 `build`로 시작하거나 없다')
        return found

    def test_the_aggregate_checks_every_upstream_result(self):
        self.assertEqual([], self.problems(self.text))

    def test_dropping_the_gradle_result_check_is_caught(self):
        mutant = self.text.replace('if [ "$GRADLE" != success ]', 'if false', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual(['결과 모음에 `if [ "$GRADLE" != success ]`가 없다'], self.problems(mutant))

    def test_a_shard_named_like_the_required_check_is_caught(self):
        mutant = self.text.replace('    name: gradle (${{ matrix.shard }})\n', '    name: build (${{ matrix.shard }})\n', 1)
        self.assertNotEqual(self.text, mutant)
        self.assertEqual(['gradle 작업 이름이 `build`로 시작하거나 없다'], self.problems(mutant))


if __name__ == '__main__':
    unittest.main()
