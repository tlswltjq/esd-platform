from pathlib import Path
import unittest

import start_task

ROOT = Path(__file__).resolve().parents[2]


class FillBodyTest(unittest.TestCase):
    def test_fills_only_the_claim_lines_of_the_real_template(self):
        template = (ROOT / start_task.TEMPLATE).read_text(encoding='utf-8')
        body = start_task.fill_body(template, '`.kiro/specs/order/tasks.md` — "환불 $1 \\1"', [214, 215])
        self.assertIn('- 항목: `.kiro/specs/order/tasks.md` — "환불 $1 \\1"', body)
        self.assertIn('- 결정 번호: 214, 215', body)
        self.assertIn('## 리뷰 판정', body)
        self.assertEqual(template.count('\n'), body.count('\n'))

    def test_no_numbers_is_none(self):
        body = start_task.fill_body('- 항목: x\n- 결정 번호: y\n', '무엇', [])
        self.assertEqual('- 항목: 무엇\n- 결정 번호: 없음\n', body)

    def test_template_without_claim_lines_fails(self):
        with self.assertRaises(SystemExit):
            start_task.fill_body('## 무엇을 왜\n', '무엇', [])


class ReserveTest(unittest.TestCase):
    def test_consecutive(self):
        self.assertEqual([214, 215, 216], start_task.reserve(3, 214))
        self.assertEqual([], start_task.reserve(0, 214))


class BranchTest(unittest.TestCase):
    def test_rejects_protected_and_odd_names(self):
        for name in ('main', 'master', '-x', 'a..b', 'a b', 'feat/{x}'):
            with self.assertRaises(SystemExit, msg=name):
                start_task.check_branch(name)
        start_task.check_branch('feat/order-refund')


if __name__ == '__main__':
    unittest.main()
