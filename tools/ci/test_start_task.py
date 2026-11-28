from pathlib import Path
import unittest

import start_task

ROOT = Path(__file__).resolve().parents[2]


class FillBodyTest(unittest.TestCase):
    def test_fills_only_the_claim_line_of_the_real_template(self):
        template = (ROOT / start_task.TEMPLATE).read_text(encoding='utf-8')
        body = start_task.fill_body(template, '`specs/order/tasks.md` — "환불 $1 \\1"')
        self.assertIn('- 항목: `specs/order/tasks.md` — "환불 $1 \\1"', body)
        self.assertIn('## 리뷰 판정', body)
        self.assertEqual(template.count('\n'), body.count('\n'))

    def test_template_has_no_decision_number_line(self):
        # 결정 decision-name-ids: 결정 번호를 예약하지 않는다.
        template = (ROOT / start_task.TEMPLATE).read_text(encoding='utf-8')
        self.assertNotIn('결정 번호', template)

    def test_template_without_claim_line_fails(self):
        with self.assertRaises(SystemExit):
            start_task.fill_body('## 무엇을 왜\n', '무엇')


class ParserTest(unittest.TestCase):
    def test_decisions_option_is_gone(self):
        with self.assertRaises(SystemExit):
            start_task.main(['prepare', '--branch', 'feat/x', '--item', 'x', '--decisions', '1'])


class BranchTest(unittest.TestCase):
    def test_rejects_protected_and_odd_names(self):
        for name in ('main', 'master', '-x', 'a..b', 'a b', 'feat/{x}'):
            with self.assertRaises(SystemExit, msg=name):
                start_task.check_branch(name)
        start_task.check_branch('feat/order-refund')


if __name__ == '__main__':
    unittest.main()
