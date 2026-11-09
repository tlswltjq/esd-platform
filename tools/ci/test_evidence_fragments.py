from pathlib import Path
import tempfile
import unittest

import evidence_fragments

INDEX = '# 근거 색인\n\n| 날짜 | 모듈 | 검증 대상 | 결과 | 근거 |\n|---|---|---|---|---|\n| 2026-11-01 | cross | 옛 행 | 통과 | PR #1 |\n'
ROW = '| 2026-11-09 | payment | 무엇 | 통과 `a\\|b` | [PR #7](x) |'


class CellsTest(unittest.TestCase):
    def test_only_escaped_pipes_are_not_boundaries(self):
        self.assertEqual(['2026-11-09', 'payment', '무엇', '통과 `a\\|b`', '[PR #7](x)'], evidence_fragments.cells(ROW))
        # 리뷰 2: GFM에서는 인라인 코드 안의 이스케이프하지 않은 파이프도 칸 경계다.
        self.assertEqual(6, len(evidence_fragments.cells('| 2026-11-09 | a | b | `x|y` | c |')))
        self.assertIn('다섯 칸', evidence_fragments.rows('f', '| 2026-11-09 | a | b | `x|y` | c |')[1][0])
        self.assertEqual(['a', 'b \\| c'], evidence_fragments.cells('| a | b \\| c |'))
        self.assertIsNone(evidence_fragments.cells('그냥 문장'))

    def test_rows_report_shape_date_and_empty_cells(self):
        self.assertEqual([('2026-11-09', ROW)], evidence_fragments.rows('f', ROW + '\n')[0])
        self.assertTrue(evidence_fragments.rows('f', '')[1])
        self.assertIn('다섯 칸', evidence_fragments.rows('f', '| 2026-11-09 | a | b |')[1][0])
        self.assertIn('YYYY-MM-DD', evidence_fragments.rows('f', '| 11월 9일 | a | b | c | d |')[1][0])
        self.assertIn('빈 칸', evidence_fragments.rows('f', '| 2026-11-09 | a |  | c | d |')[1][0])


class PullRequestRuleTest(unittest.TestCase):
    def test_a_code_pr_needs_its_own_fragment(self):
        self.assertEqual([], evidence_fragments.check_pr(7, [('modified', 'apps/x/A.java'), ('added', 'docs/evidence.d/7.md')]))
        self.assertIn('docs/evidence.d/7.md', evidence_fragments.check_pr(7, [('modified', 'apps/x/A.java')])[0])

    def test_a_docs_only_pr_may_skip_the_fragment(self):
        self.assertEqual([], evidence_fragments.check_pr(7, [('modified', 'docs/decisions.md')]))

    def test_another_prs_fragment_and_fragment_removal_are_refused(self):
        errors = evidence_fragments.check_pr(7, [('added', 'docs/evidence.d/8.md'), ('removed', 'docs/evidence.d/6.md')])
        self.assertTrue(any('삭제는 접기 PR만' in e for e in errors))
        self.assertTrue(any('조각은 docs/evidence.d/7.md 하나' in e for e in errors))

    def test_only_a_fold_pr_edits_the_index(self):
        fold = [('modified', 'docs/evidence.md'), ('removed', 'docs/evidence.d/5.md'), ('removed', 'docs/evidence.d/6.md')]
        self.assertEqual([], evidence_fragments.check_pr(9, fold))
        self.assertEqual([], evidence_fragments.check_pr(9, [('modified', 'docs/evidence.md')]))
        mixed = evidence_fragments.check_pr(9, [('modified', 'docs/evidence.md'), ('modified', 'apps/x/A.java')])
        self.assertIn('접기 PR만', mixed[0])
        self.assertIn('apps/x/A.java', mixed[0])


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / 'docs/evidence.d').mkdir(parents=True)
        (self.root / 'docs/evidence.md').write_text(INDEX, encoding='utf-8')

    def tearDown(self):
        self.tmp.cleanup()

    def fragment(self, name, text):
        (self.root / 'docs/evidence.d' / name).write_text(text, encoding='utf-8')

    def test_check_reports_bad_names_and_rows(self):
        self.fragment('7.md', ROW + '\n')
        self.assertEqual([], evidence_fragments.check(self.root))
        self.fragment('note.md', ROW + '\n')
        self.fragment('8.md', '| 2026-11-09 | a |\n')
        errors = evidence_fragments.check(self.root)
        self.assertEqual(2, len(errors))

    def test_fold_appends_rows_by_date_then_pr_and_removes_fragments(self):
        self.fragment('12.md', '| 2026-11-09 | b | 열둘 | 통과 | PR #12 |\n')
        self.fragment('9.md', '| 2026-11-09 | a | 아홉 | 통과 | PR #9 |\n| 2026-11-08 | a | 아홉 앞 | 통과 | PR #9 |\n')
        errors, count = evidence_fragments.fold(self.root)
        self.assertEqual(([], 3), (errors, count))
        tail = (self.root / 'docs/evidence.md').read_text(encoding='utf-8').split('\n')[-4:-1]
        self.assertEqual(['아홉 앞', '아홉', '열둘'], [evidence_fragments.cells(line)[2] for line in tail])
        self.assertEqual([], list((self.root / 'docs/evidence.d').iterdir()))

    def test_fold_changes_nothing_when_a_fragment_is_invalid(self):
        self.fragment('9.md', '| 2026-11-09 | a | 아홉 | 통과 | PR #9 |\n')
        self.fragment('10.md', '깨진 행\n')
        errors, count = evidence_fragments.fold(self.root)
        self.assertTrue(errors)
        self.assertEqual(0, count)
        self.assertEqual(INDEX, (self.root / 'docs/evidence.md').read_text(encoding='utf-8'))
        self.assertEqual(2, len(list((self.root / 'docs/evidence.d').iterdir())))

    def test_a_rename_is_a_removal_and_an_addition(self):
        # 리뷰 1: 다른 PR의 조각을 자기 번호로 옮기면 그 조각 삭제로 걸린다.
        changes = self.root / 'changes.txt'
        changes.write_text('modified\tapps/x/A.java\nrenamed\tdocs/evidence.d/7.md\tdocs/evidence.d/5.md\n', encoding='utf-8')
        parsed = evidence_fragments.read_changes(changes)
        self.assertEqual([('modified', 'apps/x/A.java'), ('removed', 'docs/evidence.d/5.md'), ('added', 'docs/evidence.d/7.md')], parsed)
        self.assertTrue(any('삭제는 접기 PR만' in e for e in evidence_fragments.check_pr(7, parsed)))
        moved = evidence_fragments.read_changes(self.write_changes('renamed\tdocs/old.md\tdocs/evidence.md\n'))
        self.assertIn(('removed', 'docs/evidence.md'), moved)

    def write_changes(self, text):
        path = self.root / 'moved.txt'
        path.write_text(text, encoding='utf-8')
        return path

    def test_main_pr_command_reads_tab_separated_changes(self):
        changes = self.root / 'changes.txt'
        changes.write_text('modified\tapps/x/A.java\nadded\tdocs/evidence.d/7.md\n', encoding='utf-8')
        self.assertEqual(0, evidence_fragments.main(['pr', '--number', '7', '--changes', str(changes)]))
        self.assertEqual(1, evidence_fragments.main(['pr', '--number', '8', '--changes', str(changes)]))


if __name__ == '__main__':
    unittest.main()
