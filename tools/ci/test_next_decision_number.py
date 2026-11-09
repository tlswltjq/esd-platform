from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

import next_decision_number

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/ci/next_decision_number.py'
MAIN = '<a id="one"></a>\n## 1. 첫째\n<a id="two"></a>\n## 2. 둘째\n'


def body(numbers, section='맡은 항목'):
    return ('## ' + section + '\n\n<!-- 주석 안의 줄은 세지 않는다\n- 결정 번호: 99\n-->\n'
            '- 항목: `.kiro/specs/x/tasks.md` — "무엇"\n- 결정 번호: ' + numbers + '\n\n'
            '## 무엇을 왜\n\n- 결정 번호: 50 은 다른 절이라 세지 않는다\n')


class ReservedNumbersTest(unittest.TestCase):
    def test_single_and_multiple(self):
        self.assertEqual([4], next_decision_number.reserved_numbers(body('4')))
        self.assertEqual([4, 5], next_decision_number.reserved_numbers(body('4, 5')))
        self.assertEqual([4, 5], next_decision_number.reserved_numbers(body('4·5')))

    def test_none_and_missing(self):
        self.assertEqual([], next_decision_number.reserved_numbers(body('없음')))
        self.assertEqual([], next_decision_number.reserved_numbers(body('7', section='다른 절')))
        self.assertEqual([], next_decision_number.reserved_numbers(''))
        self.assertEqual([], next_decision_number.reserved_numbers(None))

    def test_number_line_outside_the_section_is_ignored(self):
        text = '## 맡은 항목\n\n- 항목: 무엇\n\n## 무엇을 왜\n\n- 결정 번호: 50\n'
        self.assertEqual([], next_decision_number.reserved_numbers(text))

    def test_crlf_body(self):
        self.assertEqual([6], next_decision_number.reserved_numbers(body('6').replace('\n', '\r\n')))


class NextNumberTest(unittest.TestCase):
    def test_main_only(self):
        self.assertEqual((3, 2, {}), next_decision_number.next_number(MAIN, []))

    def test_open_reservations_raise_the_next_number(self):
        prs = [{'number': 10, 'body': body('3')}, {'number': 11, 'body': body('없음')},
               {'number': 12, 'body': body('5, 4')}]
        self.assertEqual((6, 2, {10: [3], 12: [5, 4]}), next_decision_number.next_number(MAIN, prs))

    def test_reservation_already_merged_does_not_lower(self):
        prs = [{'number': 10, 'body': body('1')}]
        self.assertEqual((3, 2, {10: [1]}), next_decision_number.next_number(MAIN, prs))


class CliTest(unittest.TestCase):
    def test_offline_files(self):
        with tempfile.TemporaryDirectory() as temp:
            decisions = Path(temp) / 'decisions.md'
            decisions.write_text(MAIN, encoding='utf-8')
            prs = Path(temp) / 'prs.json'
            prs.write_text(json.dumps([{'number': 178, 'body': body('3')}]), encoding='utf-8')
            result = subprocess.run([sys.executable, '-I', '-B', str(SCRIPT), '--decisions', str(decisions),
                                     '--prs-json', str(prs)], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    universal_newlines=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('4', result.stdout.splitlines()[0])
        self.assertIn('main 최대 2', result.stdout)
        self.assertIn('#178: 3', result.stdout)


if __name__ == '__main__':
    unittest.main()
