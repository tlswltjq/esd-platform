from pathlib import Path
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout

import review_gate

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github' / 'workflows' / 'review-gate.yml'
TEMPLATE = ROOT / '.github' / 'pull_request_template.md'
CODE = ['apps/license/src/main/java/A.java', 'docs/decisions.md']
DOCS = ['docs/decisions.md', 'AGENTS.md']


def body(table_rows, after='## 남은 범위\n\n- 없음\n'):
    return (
        '## 검증\n\n| 검증 | 결과 |\n|---|---|\n| 단위 | 통과 |\n\n'
        '## 리뷰 판정\n\n'
        '<!-- 지적마다 판정과 이유. 사람이 판정한다. -->\n'
        '| 지적 | 판정 (채택 / 부분 반영 / 기각) | 이유 |\n'
        '|---|---|---|\n'
        + ''.join(row + '\n' for row in table_rows)
        + '\n' + after
    )


class ScopeTest(unittest.TestCase):
    """결정 108: 변경 파일이 모두 *.md인 PR만 리뷰 대상에서 빠진다."""

    def test_markdown_only_pull_request_passes_without_a_review_table(self):
        self.assertEqual(review_gate.check('', DOCS), [])

    def test_any_non_markdown_file_needs_review(self):
        self.assertTrue(review_gate.needs_review(['README.md', '.github/workflows/ci.yml']))
        self.assertFalse(review_gate.needs_review(['README.md', 'docs/a.md']))

    def test_pull_request_without_files_passes(self):
        self.assertEqual(review_gate.check('', []), [])


class ReviewTableTest(unittest.TestCase):
    """결정 109: 코드 PR은 `리뷰 판정` 표에 행이 있고, 지적마다 판정이 있어야 한다."""

    def test_missing_section_fails(self):
        errors = review_gate.check('## 검증\n\n| a | b |\n|---|---|\n| x | 채택 |\n', CODE)
        self.assertEqual(len(errors), 1)
        self.assertIn('리뷰 판정', errors[0])

    def test_untouched_template_table_fails(self):
        errors = review_gate.check(TEMPLATE.read_text(encoding='utf-8'), CODE)
        self.assertEqual(len(errors), 1)
        self.assertIn('비어', errors[0])

    def test_each_verdict_passes(self):
        for verdict in ('채택', '부분 반영', '기각', '**채택**', '채택 (사용자 확정: "R1·R3도 채택으로 확정해")'):
            with self.subTest(verdict=verdict):
                self.assertEqual(review_gate.check(body(['| R1 잠금 전 읽기 | ' + verdict + ' | 이유 |']), CODE), [])

    def test_finding_without_verdict_fails_and_names_the_finding(self):
        errors = review_gate.check(body(['| R1 잠금 전 읽기 | 채택 | 이유 |', '| R2 옛 환불 잔류 |  | 검토 중 |']), CODE)
        self.assertEqual(len(errors), 1)
        self.assertIn('R2 옛 환불 잔류', errors[0])

    def test_unknown_verdict_fails(self):
        errors = review_gate.check(body(['| 교차 리뷰 없음 | — | 설정·문서 변경 |']), CODE)
        self.assertEqual(len(errors), 1)
        self.assertIn('교차 리뷰 없음', errors[0])

    def test_no_findings_row_passes(self):
        self.assertEqual(review_gate.check(body(['| 지적 없음 (리뷰어: Codex, 독립 문맥) | — | — |']), CODE), [])

    def test_verdict_cell_missing_entirely_fails(self):
        self.assertEqual(len(review_gate.check(body(['| R1 잠금 전 읽기 |']), CODE)), 1)

    def test_empty_finding_with_verdict_fails(self):
        self.assertEqual(len(review_gate.check(body(['|  | 채택 | 이유 |']), CODE)), 1)

    def test_html_comment_does_not_count_as_a_row(self):
        text = body([]).replace('<!-- 지적마다', '<!--\n| R1 | 채택 | 이유 |\n지적마다')
        self.assertEqual(len(review_gate.check(text, CODE)), 1)

    def test_a_whole_table_inside_an_html_comment_does_not_count(self):
        hidden = '## 리뷰 판정\n\n<!--\n| 지적 | 판정 |\n|---|---|\n| R1 | 채택 |\n-->\n'
        self.assertEqual(len(review_gate.check(hidden, CODE)), 1)

    def test_rows_after_the_next_heading_do_not_count(self):
        text = body([], after='## 남은 범위\n\n| 항목 | 상태 |\n|---|---|\n| R1 | 채택 |\n')
        self.assertEqual(len(review_gate.check(text, CODE)), 1)

    def test_second_table_header_is_not_a_finding(self):
        text = body(['| R1 | 채택 | 이유 |']).replace(
            '\n## 남은 범위', '\n| 추가 지적 | 판정 |\n|---|---|\n| R2 | 기각 |\n\n## 남은 범위')
        self.assertEqual(review_gate.check(text, CODE), [])

    def test_escaped_pipe_stays_inside_the_cell(self):
        self.assertEqual(review_gate.check(body(['| `a \\| b` 조건 누락 | 채택 | 이유 |']), CODE), [])

    def test_windows_line_endings(self):
        self.assertEqual(review_gate.check(body(['| R1 | 채택 | 이유 |']).replace('\n', '\r\n'), CODE), [])


class CrossReviewFindingsTest(unittest.TestCase):
    """PR #125 교차 리뷰 R1~R5 (판정: R1·R2·R4 채택, R3·R5 부분 반영)."""

    SECTION = '## 리뷰 판정\n\n'
    TABLE = '| 지적 | 판정 |\n|---|---|\n| R1 | 채택 |\n'

    def test_r1_an_h1_heading_ends_the_section(self):
        self.assertEqual(len(review_gate.check(self.SECTION + '# 다른 절\n\n' + self.TABLE, CODE)), 1)

    def test_r1_an_indented_h2_heading_ends_the_section(self):
        for indent in (' ', '  ', '   '):
            with self.subTest(indent=len(indent)):
                self.assertEqual(len(review_gate.check(self.SECTION + indent + '## 남은 범위\n\n' + self.TABLE, CODE)), 1)

    def test_r1_four_spaces_is_not_a_heading(self):
        self.assertEqual(review_gate.check(self.SECTION + '    ## 코드처럼 들여쓴 줄\n\n' + self.TABLE, CODE), [])

    def test_r1_an_h3_heading_stays_inside_the_section(self):
        self.assertEqual(review_gate.check(self.SECTION + '### Codex 리뷰\n\n' + self.TABLE, CODE), [])

    def test_r1_hash_without_space_is_not_a_heading(self):
        self.assertEqual(review_gate.check(self.SECTION + '#태그\n\n' + self.TABLE, CODE), [])

    def test_r2_rows_inside_a_code_fence_do_not_count(self):
        for fence in ('```', '~~~', '```markdown'):
            with self.subTest(fence=fence):
                text = self.SECTION + fence + '\n' + self.TABLE + fence[:3] + '\n'
                self.assertEqual(len(review_gate.check(text, CODE)), 1)

    def test_r2_a_table_after_a_closed_fence_counts(self):
        text = self.SECTION + '```\n예시\n```\n\n' + self.TABLE
        self.assertEqual(review_gate.check(text, CODE), [])

    def test_r2_a_row_without_header_and_separator_does_not_count(self):
        self.assertEqual(len(review_gate.check(self.SECTION + '| R1 | 채택 |\n', CODE)), 1)

    def test_r2_several_rows_without_a_separator_do_not_count(self):
        text = self.SECTION + '| 지적 | 판정 |\n| R0 | 메모 |\n| R1 | 채택 |\n'
        self.assertEqual(len(review_gate.check(text, CODE)), 1)

    def test_r2_rows_after_a_blank_line_belong_to_no_table(self):
        text = self.SECTION + '| 지적 | 판정 |\n|---|---|\n\n| R1 | 채택 |\n'
        self.assertEqual(len(review_gate.check(text, CODE)), 1)

    def test_r3_fewer_listed_files_than_changed_files_fails(self):
        errors = review_gate.check('', DOCS, expected_files=3001)
        self.assertEqual(len(errors), 1)
        self.assertIn('3001', errors[0])

    def test_r3_matching_file_count_keeps_markdown_only_passing(self):
        self.assertEqual(review_gate.check('', DOCS, expected_files=len(DOCS)), [])

    def test_r4_no_findings_row_without_a_reviewer_fails(self):
        for row in ('| 지적 없음 |  |  |', '| 지적 없음 (리뷰어: ) | — | — |', '| 지적 없음 | — | 리뷰어: |'):
            with self.subTest(row=row):
                errors = review_gate.check(body([row]), CODE)
                self.assertEqual(len(errors), 1)
                self.assertIn('리뷰어', errors[0])

    def test_r4_reviewer_may_be_in_any_cell(self):
        self.assertEqual(review_gate.check(body(['| 지적 없음 | — | 리뷰어: Codex(독립 문맥) |']), CODE), [])

    def test_r5_a_table_without_outer_pipes_fails_with_the_accepted_format(self):
        errors = review_gate.check(self.SECTION + '지적 | 판정\n---|---\nR1 | 채택\n', CODE)
        self.assertEqual(len(errors), 1)
        self.assertIn('줄 앞뒤', errors[0])


class CommandLineTest(unittest.TestCase):

    def run_main(self, text, files, expected=None):
        with tempfile.TemporaryDirectory() as directory:
            body_path = os.path.join(directory, 'body.md')
            files_path = os.path.join(directory, 'files.txt')
            Path(body_path).write_text(text, encoding='utf-8')
            Path(files_path).write_text('\n'.join(files) + '\n', encoding='utf-8')
            argv = ['--body', body_path, '--files', files_path]
            if expected is not None:
                argv += ['--expected-files', str(expected)]
            out = io.StringIO()
            with redirect_stdout(out):
                code = review_gate.main(argv)
            return code, out.getvalue()

    def test_r3_truncated_markdown_only_list_exits_one(self):
        code, out = self.run_main('', DOCS, expected=3001)
        self.assertEqual(code, 1)
        self.assertIn('3001', out)

    def test_r3_matching_count_exits_zero(self):
        self.assertEqual(self.run_main('', DOCS, expected=len(DOCS))[0], 0)

    def test_failure_exits_one_and_explains(self):
        code, out = self.run_main(body([]), CODE)
        self.assertEqual(code, 1)
        self.assertIn('결정 108', out)

    def test_success_exits_zero(self):
        self.assertEqual(self.run_main(body(['| R1 | 기각 | 이유 |']), CODE)[0], 0)

    def test_markdown_only_exits_zero(self):
        code, out = self.run_main('', DOCS)
        self.assertEqual(code, 0)
        self.assertIn('*.md', out)


class WorkflowContractTest(unittest.TestCase):
    """결정 109: 규칙셋의 필수 검사 이름과 트리거가 워크플로와 맞아야 PR이 대기에서 풀린다."""

    def setUp(self):
        raw = WORKFLOW.read_text(encoding='utf-8')
        self.text = '\n'.join(line for line in raw.splitlines() if not line.lstrip().startswith('#')) + '\n'

    def test_job_name_matches_the_required_check(self):
        self.assertIn('    name: ' + review_gate.CHECK_NAME + '\n', self.text)

    def test_runs_again_when_the_body_is_edited(self):
        self.assertIn('types: [opened, edited, synchronize, reopened]', self.text)

    def test_has_no_path_filter(self):
        self.assertNotIn('paths', self.text.split('permissions:')[0])

    def test_body_reaches_the_script_through_the_environment_only(self):
        run_lines = [line for line in self.text.splitlines() if 'review_gate.py' in line or 'printf' in line]
        self.assertTrue(run_lines)
        for line in run_lines:
            self.assertNotIn('${{', line)

    def test_r3_passes_the_changed_file_count_from_the_api(self):
        script_line = next(line for line in self.text.splitlines() if 'tools/ci/review_gate.py' in line)
        self.assertIn('--expected-files', script_line)
        self.assertIn('.changed_files', self.text)


if __name__ == '__main__':
    unittest.main()
