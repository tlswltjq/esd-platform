import argparse
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

import pr_finish
import review_gate

BUILD = 'build (unit · architecture · integration · migration)'


def run(name, conclusion, started, status='COMPLETED'):
    return {'__typename': 'CheckRun', 'name': name, 'status': status, 'conclusion': conclusion, 'startedAt': started}


def verdict_body(rows):
    return ('## 리뷰 판정\n\n| 지적 | 판정 (채택 / 부분 반영 / 기각) | 이유 |\n|---|---|---|\n'
            + ''.join('| ' + ' | '.join(row) + ' |\n' for row in rows) + '\n## 남은 범위\n')


def pr(**overrides):
    base = {'number': 7, 'state': 'OPEN', 'isDraft': False, 'mergeStateStatus': 'CLEAN', 'headRefOid': 'a' * 40,
            'body': verdict_body([['지적 없음 (리뷰어: Codex, 독립 문맥)', '—', '—']]),
            'statusCheckRollup': [run(BUILD, 'SUCCESS', '2026-11-23T09:00:02Z'),
                                  run('review-gate', 'SUCCESS', '2026-11-23T09:00:01Z')]}
    base.update(overrides)
    return base


class ProblemsTest(unittest.TestCase):
    def test_clean_pr_has_no_problems(self):
        self.assertEqual([], pr_finish.problems(pr()))

    def test_draft_is_not_mergeable_even_when_clean(self):
        # 결정 137: draft의 건너뛴 build는 CLEAN으로 보여도 통과가 아니다.
        found = pr_finish.problems(pr(isDraft=True))
        self.assertTrue(any('draft' in item for item in found))

    def test_latest_build_decides(self):
        # 결정 118: head에서 가장 최근에 시작한 build의 결과로 본다.
        earlier_success = [run(BUILD, 'SUCCESS', '2026-11-23T09:00:00Z'), run(BUILD, 'FAILURE', '2026-11-23T09:05:00Z'),
                           run('review-gate', 'SUCCESS', '2026-11-23T09:00:01Z')]
        self.assertTrue(any('FAILURE' in item for item in pr_finish.problems(pr(statusCheckRollup=earlier_success))))
        later_success = [run(BUILD, 'CANCELLED', '2026-11-23T09:00:00Z'), run(BUILD, 'SUCCESS', '2026-11-23T09:00:04Z'),
                         run('review-gate', 'SUCCESS', '2026-11-23T09:00:01Z')]
        self.assertEqual([], pr_finish.problems(pr(statusCheckRollup=later_success)))

    def test_skipped_or_missing_build_is_not_success(self):
        skipped = [run(BUILD, 'SKIPPED', '2026-11-23T09:00:00Z'), run('review-gate', 'SUCCESS', '2026-11-23T09:00:01Z')]
        self.assertTrue(pr_finish.problems(pr(statusCheckRollup=skipped)))
        self.assertTrue(any('`build`' in item for item in pr_finish.problems(pr(statusCheckRollup=[]))))

    def test_in_progress_build_is_not_success(self):
        running = [run(BUILD, None, '2026-11-23T09:00:00Z', status='IN_PROGRESS'),
                   run('review-gate', 'SUCCESS', '2026-11-23T09:00:01Z')]
        self.assertTrue(pr_finish.problems(pr(statusCheckRollup=running)))

    def test_not_clean(self):
        self.assertTrue(pr_finish.problems(pr(mergeStateStatus='BLOCKED')))


class PendingUserVerdictsTest(unittest.TestCase):
    def test_rejection_and_partial_need_user_confirmation(self):
        body = verdict_body([['R1 (medium) 무엇', '기각 — 이유', '이유'], ['R2 (low) 무엇', '부분 반영', '이유'],
                             ['R3 (medium) 무엇', '채택 (AI 판정)', '이유']])
        self.assertEqual(['R1 (medium) 무엇', 'R2 (low) 무엇'], review_gate.pending_user_verdicts(body))

    def test_high_needs_user_confirmation_even_if_adopted(self):
        body = verdict_body([['R1 (high) 무엇', '채택', '이유']])
        self.assertEqual(['R1 (high) 무엇'], review_gate.pending_user_verdicts(body))

    def test_user_confirmed_rows_pass(self):
        body = verdict_body([['R1 (high) 무엇', '채택 (사용자 확정: "채택")', '이유'],
                             ['R2 (low) 무엇', '기각 (사용자 확정: "기각해")', '이유']])
        self.assertEqual([], review_gate.pending_user_verdicts(body))

    def test_waiting_for_user_is_not_confirmed(self):
        # PR #222 R5: "채택 (사용자 확정 대기)"는 아직 사용자 판정 전이다.
        body = verdict_body([['R5 (high) 무엇', '채택 (사용자 확정 대기)', '이유']])
        self.assertEqual(['R5 (high) 무엇'], review_gate.pending_user_verdicts(body))

    def test_confirmation_counts_only_in_the_verdict_cell_and_must_be_non_empty(self):
        # PR #226 R2: 이유 칸의 언급이나 빈 원문은 확정이 아니다.
        body = verdict_body([['R1 (high) 무엇', '채택', '사용자 확정: "채택"으로 기록할 예정'],
                             ['R2 (high) 무엇', '채택 (사용자 확정: "")', '이유'],
                             ['R3 (high) 무엇', '채택 (사용자 확정: "', '이유'],
                             ['R4 (high) 무엇', '채택 (사용자 확정: "", 메모: "대기")', '이유'],
                             ['R5 (high) 무엇', '채택 (사용자 확정: "  ")', '이유']])
        self.assertEqual(['R1 (high) 무엇', 'R2 (high) 무엇', 'R3 (high) 무엇', 'R4 (high) 무엇', 'R5 (high) 무엇'],
                         review_gate.pending_user_verdicts(body))

    def test_policy_rows_need_user_confirmation(self):
        # PR #226 R3: 정책을 바꾸는 지적은 AI 판정 채택이어도 사용자가 판정한다(결정 112).
        body = verdict_body([['R1 (medium) 재시도 정책을 바꿔야 함', '채택 (AI 판정)', '이유'],
                             ['R2 (low) 문구', '채택 (AI 판정)', '정책 변경 아님을 확인했다'],
                             ['R3 (medium) 정책 변경', '채택 (사용자 확정: "채택")', '이유']])
        self.assertEqual(['R1 (medium) 재시도 정책을 바꿔야 함', 'R2 (low) 문구'], review_gate.pending_user_verdicts(body))

    def test_reasoning_level_high_in_reviewer_is_not_severity(self):
        # PR #226: "리뷰어: Codex gpt-6.1-sol high"의 high를 심각도로 읽어 병합을 막았다.
        body = verdict_body([['재리뷰(리뷰어: Codex `gpt-6.1-sol` high, `a..b`): R8 해소, 새 지적 R10', '채택 (AI 판정)', '아래'],
                             ['R8 (high, 리뷰어: Codex) 무엇', '채택 (AI 판정)', '이유'],
                             ['R9 ( High ) 무엇', '채택', '이유']])
        self.assertEqual(['R8 (high, 리뷰어: Codex) 무엇', 'R9 ( High ) 무엇'], review_gate.pending_user_verdicts(body))

    def test_word_high_inside_other_words_is_not_severity(self):
        body = verdict_body([['R1 (low) highlight 문구', '채택', '이유']])
        self.assertEqual([], review_gate.pending_user_verdicts(body))

    def test_missing_section_has_nothing_pending(self):
        self.assertEqual([], review_gate.pending_user_verdicts('## 무엇을 왜\n'))


class MergeTest(unittest.TestCase):
    def test_stops_at_first_unmergeable_pr_and_merges_nothing_after(self):
        # careers에서 draft #21 실패 뒤 #24가 먼저 병합된 사례.
        views = {1: pr(number=1, isDraft=True), 2: pr(number=2)}
        with mock.patch.object(pr_finish, 'view', side_effect=lambda n: views[n]), \
                mock.patch.object(pr_finish, 'gh') as gh, redirect_stdout(io.StringIO()):
            code = pr_finish.merge(argparse.Namespace(numbers=[1, 2]))
        self.assertEqual(1, code)
        gh.assert_not_called()

    def test_stops_when_merge_does_not_reach_merged(self):
        views = {1: pr(number=1), 2: pr(number=2)}
        with mock.patch.object(pr_finish, 'view', side_effect=lambda n: views[n]), \
                mock.patch.object(pr_finish, 'gh', return_value=(1, '', 'boom')) as gh, \
                redirect_stdout(io.StringIO()):
            code = pr_finish.merge(argparse.Namespace(numbers=[1, 2]))
        self.assertEqual(1, code)
        self.assertEqual(1, gh.call_count)

    def test_merges_in_order_with_head_pinned(self):
        states = {1: 'OPEN', 2: 'OPEN'}

        def fake_view(number):
            return pr(number=number, state=states[number])

        def fake_gh(args, check=True):
            states[int(args[2])] = 'MERGED'
            return 0, '', ''

        with mock.patch.object(pr_finish, 'view', side_effect=fake_view), \
                mock.patch.object(pr_finish, 'gh', side_effect=fake_gh) as gh, redirect_stdout(io.StringIO()):
            code = pr_finish.merge(argparse.Namespace(numbers=[1, 2]))
        self.assertEqual(0, code)
        self.assertEqual(['1', '2'], [call.args[0][2] for call in gh.call_args_list])
        self.assertIn('--match-head-commit', gh.call_args_list[0].args[0])


class WaitTest(unittest.TestCase):
    def args(self, **overrides):
        base = dict(number=7, head_sha=None, timeout=60, register_timeout=30, interval=0)
        base.update(overrides)
        return argparse.Namespace(**base)

    def test_waits_for_registration_then_completion(self):
        # PR #122: push 직후 검사가 없어도 끝내지 않고 등록을 기다린다.
        sequence = [[], [{'name': 'build', 'bucket': 'pending'}],
                    [{'name': 'build', 'bucket': 'pass'}, {'name': 'review-gate', 'bucket': 'pass'}]]
        with mock.patch.object(pr_finish, 'required_checks', side_effect=sequence), redirect_stdout(io.StringIO()):
            self.assertEqual(0, pr_finish.wait(self.args()))

    def test_one_required_check_alone_is_not_done(self):
        # PR #226 R6: review-gate만 먼저 끝나도 build 등록을 기다린다.
        sequence = [[{'name': 'review-gate', 'bucket': 'pass'}],
                    [{'name': 'review-gate', 'bucket': 'pass'}, {'name': 'build (unit)', 'bucket': 'fail'}]]
        with mock.patch.object(pr_finish, 'required_checks', side_effect=sequence), \
                mock.patch.object(pr_finish, 'view', return_value=pr()), redirect_stdout(io.StringIO()):
            self.assertEqual(1, pr_finish.wait(self.args()))
        with mock.patch.object(pr_finish, 'required_checks', return_value=[{'name': 'review-gate', 'bucket': 'pass'}]), \
                mock.patch.object(pr_finish, 'ci_started', return_value=False), redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.wait(self.args(register_timeout=-1)))

    def test_ready_waits_for_a_new_build_and_keeps_existing_review_gate(self):
        # PR #226: ready 직후 draft 때의 SKIPPED build로 끝났다. R8: review-gate는 다시 돌지 않는다.
        # R9: 시간이 아니라 이벤트 전 job 링크로 구분한다.
        gate = {'name': 'review-gate', 'bucket': 'pass', 'link': 'job/1'}
        old_build = {'name': 'build (unit)', 'bucket': 'skipping', 'link': 'job/2'}
        new_build = {'name': 'build (unit)', 'bucket': 'pass', 'link': 'job/3'}
        sequence = [[gate, old_build], [gate, old_build], [gate, old_build, new_build]]
        with mock.patch.object(pr_finish, 'view', return_value=pr(isDraft=True)), \
                mock.patch.object(pr_finish, 'gh', return_value=(0, '', '')) as gh, \
                mock.patch.object(pr_finish, 'required_checks', side_effect=sequence), redirect_stdout(io.StringIO()):
            self.assertEqual(0, pr_finish.ready(self.args()))
        self.assertEqual(['pr', 'ready', '7'], gh.call_args.args[0])

    def test_ready_refuses_a_non_draft(self):
        with mock.patch.object(pr_finish, 'view', return_value=pr()), \
                mock.patch.object(pr_finish, 'gh') as gh, redirect_stdout(io.StringIO()):
            self.assertEqual(2, pr_finish.ready(self.args()))
        gh.assert_not_called()

    def test_edit_waits_for_a_new_review_gate_only_when_body_changes(self):
        gate_old = {'name': 'review-gate', 'bucket': 'pass', 'link': 'job/1'}
        gate_new = {'name': 'review-gate', 'bucket': 'fail', 'link': 'job/4'}
        build = {'name': 'build (unit)', 'bucket': 'pass', 'link': 'job/3'}
        with tempfile.NamedTemporaryFile('w', suffix='.md', delete=False, encoding='utf-8') as handle:
            handle.write('새 본문')
        args = argparse.Namespace(**dict(vars(self.args()), body_file=handle.name))
        with mock.patch.object(pr_finish, 'view', return_value=pr(body='옛 본문', isDraft=False)), \
                mock.patch.object(pr_finish, 'gh', return_value=(0, '', '')), \
                mock.patch.object(pr_finish, 'required_checks',
                                  side_effect=[[gate_old, build], [gate_old, build], [gate_old, build, gate_new]]), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(1, pr_finish.edit(args))
        with mock.patch.object(pr_finish, 'view', return_value=pr(body='새 본문')), \
                mock.patch.object(pr_finish, 'gh', return_value=(0, '', '')) as gh, \
                mock.patch.object(pr_finish, 'required_checks', return_value=[gate_old, build]), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(0, pr_finish.edit(args))
        gh.assert_not_called()
        # R10: 공백만 달라도 보냈으면 이전 review-gate로 통과하지 않는다.
        with mock.patch.object(pr_finish, 'view', return_value=pr(body='새 본문\n')), \
                mock.patch.object(pr_finish, 'ci_started', return_value=False), \
                mock.patch.object(pr_finish, 'gh', return_value=(0, '', '')) as gh, \
                mock.patch.object(pr_finish, 'required_checks', return_value=[gate_old, build]), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.edit(argparse.Namespace(**dict(vars(args), register_timeout=-1))))
        self.assertEqual(1, sum(1 for call in gh.call_args_list if call.args[0][:2] == ['pr', 'edit']))
        Path(handle.name).unlink()

    def test_skipping_build_is_failure_and_mentions_draft(self):
        checks = [{'name': 'build', 'bucket': 'skipping'}, {'name': 'review-gate', 'bucket': 'pass'}]
        out = io.StringIO()
        with mock.patch.object(pr_finish, 'required_checks', return_value=checks), \
                mock.patch.object(pr_finish, 'view', return_value=pr(isDraft=True)), redirect_stdout(out):
            self.assertEqual(1, pr_finish.wait(self.args()))
        self.assertIn('gh pr ready', out.getvalue())

    def test_a_non_draft_ignores_the_skipped_build_from_its_draft_days(self):
        # PR #244: ready 뒤 새 build가 등록되기 전(결정 217)에는 draft 때 건너뛴 build만 보인다. ci.yml은 draft에서만
        # build를 건너뛰므로, draft가 아닌 PR의 skipping build는 지난 결과다. 실패로 끝내지 않고 새 build를 기다린다.
        gate = {'name': 'review-gate', 'bucket': 'pass', 'link': 'job/1'}
        skipped = {'name': BUILD, 'bucket': 'skipping', 'link': 'job/2'}
        new_build = {'name': BUILD, 'bucket': 'pass', 'link': 'job/3'}
        sequence = [[gate, skipped], [gate, skipped], [gate, skipped, new_build]]
        with mock.patch.object(pr_finish, 'required_checks', side_effect=sequence), \
                mock.patch.object(pr_finish, 'view', return_value=pr()), \
                mock.patch.object(pr_finish, 'ci_started', return_value=True), redirect_stdout(io.StringIO()):
            self.assertEqual(0, pr_finish.wait(self.args(register_timeout=-1)))
        # 새 build가 실패하면 그대로 실패다.
        failed = {'name': BUILD, 'bucket': 'fail', 'link': 'job/3'}
        with mock.patch.object(pr_finish, 'required_checks', side_effect=[[gate, skipped], [gate, skipped, failed]]), \
                mock.patch.object(pr_finish, 'view', return_value=pr()), \
                mock.patch.object(pr_finish, 'ci_started', return_value=True), redirect_stdout(io.StringIO()):
            self.assertEqual(1, pr_finish.wait(self.args(register_timeout=-1)))
        # 새 CI도 시작되지 않았으면 등록 시간 초과(3)다. 건너뛴 build를 실패(1)로 보고하지 않는다.
        with mock.patch.object(pr_finish, 'required_checks', return_value=[gate, skipped]), \
                mock.patch.object(pr_finish, 'view', return_value=pr()), \
                mock.patch.object(pr_finish, 'ci_started', return_value=False), redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.wait(self.args(register_timeout=-1)))

    def test_an_older_success_does_not_stand_in_for_a_newer_skipped_build(self):
        # PR #245 리뷰 1: 같은 head에 예전 성공 build와 더 최근의 건너뛴 build가 함께 보이면, 건너뛴 것만 빼고 예전
        # 성공으로 통과하지 않는다. 가장 최근 세대만 보고 새 build를 기다린다.
        gate = {'name': 'review-gate', 'bucket': 'pass', 'startedAt': '2026-11-24T09:00:00Z'}
        old_pass = {'name': BUILD, 'bucket': 'pass', 'startedAt': '2026-11-24T09:00:00Z'}
        skipped = {'name': BUILD, 'bucket': 'skipping', 'startedAt': '2026-11-24T09:10:00Z'}
        queued = {'name': BUILD, 'bucket': 'pending', 'startedAt': '0001-01-01T00:00:00Z'}
        new_fail = {'name': BUILD, 'bucket': 'fail', 'startedAt': '2026-11-24T09:11:00Z'}
        sequence = [[gate, old_pass, skipped], [gate, old_pass, skipped, queued], [gate, old_pass, skipped, new_fail]]
        reads = mock.Mock(side_effect=sequence)
        with mock.patch.object(pr_finish, 'required_checks', reads), \
                mock.patch.object(pr_finish, 'view', return_value=pr()), \
                mock.patch.object(pr_finish, 'ci_started', return_value=True), redirect_stdout(io.StringIO()):
            self.assertEqual(1, pr_finish.wait(self.args(register_timeout=-1)))
        self.assertEqual(3, reads.call_count)

    def test_no_registration_times_out(self):
        with mock.patch.object(pr_finish, 'required_checks', return_value=[]), \
                mock.patch.object(pr_finish, 'ci_started', return_value=False), redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.wait(self.args(register_timeout=-1)))

    def test_a_started_ci_keeps_waiting_for_the_late_build_check(self):
        # 결정 217: build는 조각이 끝난 뒤 등록된다. plan·gradle이 이벤트 뒤에 생겼으면 등록 시간 초과로 끝내지 않는다.
        gate = {'name': 'review-gate', 'bucket': 'pass'}
        sequence = [[gate], [gate], [gate, {'name': 'build (unit)', 'bucket': 'pass'}]]
        with mock.patch.object(pr_finish, 'required_checks', side_effect=sequence), \
                mock.patch.object(pr_finish, 'ci_started', return_value=True), redirect_stdout(io.StringIO()):
            self.assertEqual(0, pr_finish.wait(self.args(register_timeout=-1)))

    def test_the_grace_applies_only_to_a_missing_build_and_a_new_ci(self):
        # PR #234 3차 리뷰: review-gate가 없거나, plan·gradle이 이벤트 전 것이면 등록 제한대로 끝낸다.
        build_only = [{'name': 'build (unit)', 'bucket': 'pass'}]
        with mock.patch.object(pr_finish, 'required_checks', return_value=build_only), \
                mock.patch.object(pr_finish, 'ci_started', return_value=True) as started, redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.wait(self.args(register_timeout=-1)))
        started.assert_not_called()
        gate = [{'name': 'review-gate', 'bucket': 'pass'}]
        listed = json.dumps([{'name': 'plan (scope)', 'link': 'job/old'}])
        with mock.patch.object(pr_finish, 'required_checks', return_value=gate), \
                mock.patch.object(pr_finish, 'gh', return_value=(0, listed, '')), redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.wait(self.args(register_timeout=-1), (), frozenset(), {'job/old'}))

    def test_ready_records_every_check_from_before_the_event(self):
        gate = {'name': 'review-gate', 'bucket': 'pass', 'link': 'job/1'}
        skipped_plan = json.dumps([{'name': 'plan (scope)', 'link': 'job/5'}, gate])
        calls = []

        def fake_gh(command, check=True):
            calls.append(command)
            return (0, skipped_plan, '') if command[:2] == ['pr', 'checks'] else (0, '', '')
        with mock.patch.object(pr_finish, 'view', return_value=pr(isDraft=True)), \
                mock.patch.object(pr_finish, 'gh', side_effect=fake_gh), \
                mock.patch.object(pr_finish, 'required_checks', return_value=[gate]), redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.ready(self.args(register_timeout=-1)))
        self.assertIn(['pr', 'ready', '7'], calls)

    def test_a_failed_snapshot_before_the_event_stops_before_sending_it(self):
        # PR #234 4차 리뷰: 이벤트 전 기록이 비면 이전 plan을 새 CI로 오인하므로 ready를 보내지 않고 멈춘다.
        calls = []

        def fake_gh(command, check=True):
            calls.append(command)
            return (1, '', 'HTTP 502') if command[:2] == ['pr', 'checks'] else (0, '', '')
        with mock.patch.object(pr_finish, 'view', return_value=pr(isDraft=True)), \
                mock.patch.object(pr_finish, 'gh', side_effect=fake_gh), \
                mock.patch.object(pr_finish, 'required_checks', return_value=[]), redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit):
                pr_finish.ready(self.args())
        self.assertNotIn(['pr', 'ready', '7'], calls)
        with mock.patch.object(pr_finish, 'gh', return_value=(1, '', 'no checks reported on the branch')):
            self.assertEqual([], pr_finish.listed_checks(7, strict=True))
        with mock.patch.object(pr_finish, 'gh', return_value=(1, '', 'HTTP 502')):
            self.assertEqual([], pr_finish.listed_checks(7))
        offline = 'dial tcp: lookup api.github.com: no such host\ncheck your internet connection'
        with mock.patch.object(pr_finish, 'gh', return_value=(1, '', offline)):
            with self.assertRaises(SystemExit):
                pr_finish.listed_checks(7, strict=True)

    def test_skipped_plan_is_not_a_started_ci(self):
        listed = json.dumps([{'name': 'plan (scope)', 'link': 'job/1', 'bucket': 'skipping'},
                             {'name': 'gradle (${{ matrix.shard }})', 'link': 'job/2', 'bucket': 'skipping'}])
        with mock.patch.object(pr_finish, 'gh', return_value=(0, listed, '')):
            self.assertFalse(pr_finish.ci_started(7))
        running = json.dumps([{'name': 'gradle (unit)', 'link': 'job/3', 'bucket': 'pending'}])
        with mock.patch.object(pr_finish, 'gh', return_value=(0, running, '')):
            self.assertTrue(pr_finish.ci_started(7))

    def test_edit_during_a_running_ci_waits_for_the_late_build(self):
        # PR #234에서 본 시간 초과: 본문 수정 전에 시작된 같은 커밋의 CI를 증거로 인정한다.
        with tempfile.NamedTemporaryFile('w', suffix='.md', delete=False, encoding='utf-8') as handle:
            handle.write('새 본문')
        args = argparse.Namespace(**dict(vars(self.args(register_timeout=-1)), body_file=handle.name))
        gate_old = {'name': 'review-gate', 'bucket': 'pass', 'link': 'job/1'}
        gate_new = {'name': 'review-gate', 'bucket': 'pass', 'link': 'job/9'}
        build = {'name': 'build (unit)', 'bucket': 'pass', 'link': 'job/8'}
        running = json.dumps([{'name': 'plan (scope)', 'link': 'job/5', 'bucket': 'pass'}])

        def fake_gh(command, check=True):
            return (0, running, '') if command[:2] == ['pr', 'checks'] else (0, '', '')
        with mock.patch.object(pr_finish, 'view', return_value=pr(body='옛 본문', isDraft=False)), \
                mock.patch.object(pr_finish, 'gh', side_effect=fake_gh), \
                mock.patch.object(pr_finish, 'required_checks',
                                  side_effect=[[gate_old], [gate_old, gate_new], [gate_old, gate_new], [gate_new, build]]), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(0, pr_finish.edit(args))
        Path(handle.name).unlink()

    def test_ci_started_ignores_checks_from_before_the_event(self):
        listed = json.dumps([{'name': 'plan (scope)', 'link': 'job/1'}, {'name': 'review-gate', 'link': 'job/2'}])
        with mock.patch.object(pr_finish, 'gh', return_value=(0, listed, '')):
            self.assertTrue(pr_finish.ci_started(7))
            self.assertFalse(pr_finish.ci_started(7, {'job/1'}))
        with mock.patch.object(pr_finish, 'gh', return_value=(1, '', 'no checks reported')):
            self.assertFalse(pr_finish.ci_started(7))

    def test_waits_until_head_matches(self):
        views = [pr(headRefOid='b' * 40), pr(headRefOid='a' * 40)]
        with mock.patch.object(pr_finish, 'view', side_effect=views), \
                mock.patch.object(pr_finish, 'required_checks', return_value=[{'name': 'build', 'bucket': 'pass'},
                                                                          {'name': 'review-gate', 'bucket': 'pass'}]) \
                as checks, redirect_stdout(io.StringIO()):
            self.assertEqual(0, pr_finish.wait(self.args(head_sha='a' * 40)))
        self.assertEqual(1, checks.call_count)


if __name__ == '__main__':
    unittest.main()
