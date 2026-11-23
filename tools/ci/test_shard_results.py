"""결정 217(PR #234 R1): 결과 모음이 합친 값이 아니라 조각마다의 최신 결론으로 판정하는지 지킨다."""
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout

import shard_results

MATRIX = json.dumps({'include': [{'shard': 'unit', 'tasks': 'build'}, {'shard': 'infra-a', 'tasks': ':a:integrationTest'}]})
OK = [('plan (scope)', 'success'), ('gradle (unit)', 'success'), ('gradle (infra-a)', 'success')]


class ProblemsTest(unittest.TestCase):
    def test_every_planned_shard_succeeded(self):
        self.assertEqual([], shard_results.problems('success', 'true', MATRIX, OK))

    def test_a_cancelled_or_failed_shard_fails_even_if_others_succeeded(self):
        # 러너를 받지 못해 취소된 조각, 부분 재실행 뒤에도 남은 실패(filter=latest가 이전 시도 결과를 준다).
        for conclusion in ('cancelled', 'failure', 'skipped', 'in_progress', ''):
            with self.subTest(conclusion=conclusion):
                jobs = [OK[0], OK[1], ('gradle (infra-a)', conclusion)]
                self.assertEqual(1, len(shard_results.problems('success', 'true', MATRIX, jobs)))

    def test_a_missing_or_duplicated_shard_job_fails(self):
        self.assertIn('0개', shard_results.problems('success', 'true', MATRIX, OK[:2])[0])
        self.assertIn('2개', shard_results.problems('success', 'true', MATRIX, OK + [('gradle (unit)', 'success')])[0])

    def test_plan_failure_and_inconsistent_outputs_fail(self):
        self.assertIn('plan', shard_results.problems('failure', 'true', MATRIX, OK)[0])
        self.assertIn('true·false', shard_results.problems('success', '', MATRIX, OK)[0])
        self.assertIn('조각이 있다', shard_results.problems('success', 'false', MATRIX, OK)[0])
        self.assertIn('조각이 없다', shard_results.problems('success', 'true', '{"include":[]}', OK)[0])
        self.assertIn('matrix', shard_results.problems('success', 'true', '{', OK)[0])

    def test_skipping_gradle_needs_no_shard_jobs(self):
        self.assertEqual([], shard_results.problems('success', 'false', '{"include":[]}', [OK[0]]))


class CommandLineTest(unittest.TestCase):
    def run_main(self, jobs_text, gradle='true', matrix=MATRIX):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, 'jobs.tsv')
            with open(path, 'w', encoding='utf-8') as handle:
                handle.write(jobs_text)
            out = io.StringIO()
            with redirect_stdout(out):
                code = shard_results.main(['--plan', 'success', '--gradle', gradle, '--matrix', matrix, '--jobs', path])
        return code, out.getvalue()

    def test_reads_tab_separated_jobs(self):
        text = ''.join('{}\t{}\n'.format(name, conclusion) for name, conclusion in OK)
        self.assertEqual(0, self.run_main(text)[0])
        code, out = self.run_main(text.replace('gradle (infra-a)\tsuccess', 'gradle (infra-a)\tcancelled'))
        self.assertEqual(1, code)
        self.assertIn('cancelled', out)


if __name__ == '__main__':
    unittest.main()
