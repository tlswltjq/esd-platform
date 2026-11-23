import argparse
import io
import unittest
from contextlib import redirect_stdout
from unittest import mock

import pr_finish

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
        self.assertEqual(['R1 (medium) 무엇', 'R2 (low) 무엇'], pr_finish.pending_user_verdicts(body))

    def test_high_needs_user_confirmation_even_if_adopted(self):
        body = verdict_body([['R1 (high) 무엇', '채택', '이유']])
        self.assertEqual(['R1 (high) 무엇'], pr_finish.pending_user_verdicts(body))

    def test_user_confirmed_rows_pass(self):
        body = verdict_body([['R1 (high) 무엇', '채택 (사용자 확정: "채택")', '이유'],
                             ['R2 (low) 무엇', '기각 (사용자 확정: "기각해")', '이유']])
        self.assertEqual([], pr_finish.pending_user_verdicts(body))

    def test_waiting_for_user_is_not_confirmed(self):
        # PR #222 R5: "채택 (사용자 확정 대기)"는 아직 사용자 판정 전이다.
        body = verdict_body([['R5 (high) 무엇', '채택 (사용자 확정 대기)', '이유']])
        self.assertEqual(['R5 (high) 무엇'], pr_finish.pending_user_verdicts(body))

    def test_confirmation_counts_only_in_the_verdict_cell_and_must_be_non_empty(self):
        # PR #226 R2: 이유 칸의 언급이나 빈 원문은 확정이 아니다.
        body = verdict_body([['R1 (high) 무엇', '채택', '사용자 확정: "채택"으로 기록할 예정'],
                             ['R2 (high) 무엇', '채택 (사용자 확정: "")', '이유'],
                             ['R3 (high) 무엇', '채택 (사용자 확정: "', '이유'],
                             ['R4 (high) 무엇', '채택 (사용자 확정: "", 메모: "대기")', '이유'],
                             ['R5 (high) 무엇', '채택 (사용자 확정: "  ")', '이유']])
        self.assertEqual(['R1 (high) 무엇', 'R2 (high) 무엇', 'R3 (high) 무엇', 'R4 (high) 무엇', 'R5 (high) 무엇'],
                         pr_finish.pending_user_verdicts(body))

    def test_policy_rows_need_user_confirmation(self):
        # PR #226 R3: 정책을 바꾸는 지적은 AI 판정 채택이어도 사용자가 판정한다(결정 112).
        body = verdict_body([['R1 (medium) 재시도 정책을 바꿔야 함', '채택 (AI 판정)', '이유'],
                             ['R2 (low) 문구', '채택 (AI 판정)', '정책 변경 아님을 확인했다'],
                             ['R3 (medium) 정책 변경', '채택 (사용자 확정: "채택")', '이유']])
        self.assertEqual(['R1 (medium) 재시도 정책을 바꿔야 함', 'R2 (low) 문구'], pr_finish.pending_user_verdicts(body))

    def test_word_high_inside_other_words_is_not_severity(self):
        body = verdict_body([['R1 (low) highlight 문구', '채택', '이유']])
        self.assertEqual([], pr_finish.pending_user_verdicts(body))

    def test_missing_section_has_nothing_pending(self):
        self.assertEqual([], pr_finish.pending_user_verdicts('## 무엇을 왜\n'))


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
        base = dict(number=7, head_sha=None, timeout=60, register_timeout=30, interval=0, new_runs=False)
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
                redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.wait(self.args(register_timeout=-1)))

    def test_new_runs_ignores_results_from_before_ready(self):
        # PR #226: gh pr ready 직후 draft 때의 SKIPPED build를 읽고 끝났다.
        old = [{'name': 'build (unit)', 'bucket': 'skipping', 'startedAt': '2000-01-01T00:00:00Z'},
               {'name': 'review-gate', 'bucket': 'pass', 'startedAt': '2000-01-01T00:00:00Z'}]
        new = [{'name': 'build (unit)', 'bucket': 'pass', 'startedAt': '2999-01-01T00:00:00Z'},
               {'name': 'review-gate', 'bucket': 'pass', 'startedAt': '2999-01-01T00:00:00Z'}]
        with mock.patch.object(pr_finish, 'required_checks', side_effect=[old, old + new]), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(0, pr_finish.wait(self.args(new_runs=True)))
        not_started = [{'name': 'build (unit)', 'bucket': 'pending', 'startedAt': '0001-01-01T00:00:00Z'}] + new[1:]
        self.assertEqual(new[1:], pr_finish.fresh(not_started, '2026-01-01T00:00:00Z'))

    def test_skipping_build_is_failure_and_mentions_draft(self):
        checks = [{'name': 'build', 'bucket': 'skipping'}, {'name': 'review-gate', 'bucket': 'pass'}]
        out = io.StringIO()
        with mock.patch.object(pr_finish, 'required_checks', return_value=checks), \
                mock.patch.object(pr_finish, 'view', return_value=pr(isDraft=True)), redirect_stdout(out):
            self.assertEqual(1, pr_finish.wait(self.args()))
        self.assertIn('gh pr ready', out.getvalue())

    def test_no_registration_times_out(self):
        with mock.patch.object(pr_finish, 'required_checks', return_value=[]), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(3, pr_finish.wait(self.args(register_timeout=-1)))

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
