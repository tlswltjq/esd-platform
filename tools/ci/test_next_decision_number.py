from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

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

    def test_fenced_examples_are_ignored(self):
        # 리뷰 R2: 코드 블록 안의 예시는 예약이 아니다.
        whole = '```\n## 맡은 항목\n- 결정 번호: 999\n```\n\n' + body('140')
        inside = '## 맡은 항목\n\n```\n- 결정 번호: 999\n```\n- 결정 번호: 140\n'
        self.assertEqual([140], next_decision_number.reserved_numbers(whole))
        self.assertEqual([140], next_decision_number.reserved_numbers(inside))

    def test_inline_code_reservation_is_read(self):
        # 리뷰 R4: 예약 줄의 값이 인라인 코드여도 번호를 읽는다.
        self.assertEqual([140], next_decision_number.reserved_numbers(body('`140`')))
        self.assertEqual([138, 139], next_decision_number.reserved_numbers(body('`138`, `139`')))

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


def git(*args, cwd=None):
    subprocess.run(['git', '-c', 'user.email=t@t', '-c', 'user.name=t'] + list(args), cwd=cwd, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


class CliTest(unittest.TestCase):
    def test_reads_open_prs_before_main(self):
        # 리뷰 R1: 두 읽기 사이에 병합된 PR은 PR 목록이나 새 main 중 한쪽에 반드시 보인다.
        calls = []
        with mock.patch.object(next_decision_number, 'read_open_prs', lambda root: calls.append('prs') or []), \
                mock.patch.object(next_decision_number, 'read_main',
                                  lambda root, fetch: calls.append('main:' + str(fetch)) or MAIN), \
                mock.patch('builtins.print'):
            next_decision_number.main([])
        self.assertEqual(['prs', 'main:True'], calls)

    def run_on_stale_clone(self, refspec=None):
        """로컬 origin/main이 2에 머문 사이 원격에 3이 병합되고, 그 PR은 열린 목록에서 빠졌다."""
        with tempfile.TemporaryDirectory() as temp:
            temp = Path(temp)
            git('init', '-q', '--bare', str(temp / 'remote.git'))
            seed = temp / 'seed'
            git('init', '-q', str(seed))
            git('checkout', '-q', '-b', 'main', cwd=seed)
            (seed / 'docs').mkdir()
            (seed / 'docs/decisions.md').write_text(MAIN, encoding='utf-8')
            git('add', '-A', cwd=seed)
            git('commit', '-q', '-m', 'two', cwd=seed)
            git('remote', 'add', 'origin', str(temp / 'remote.git'), cwd=seed)
            git('push', '-q', 'origin', 'main', cwd=seed)
            git('clone', '-q', '-b', 'main', str(temp / 'remote.git'), str(temp / 'work'))
            if refspec is not None:
                git('config', 'remote.origin.fetch', refspec, cwd=temp / 'work')
            (seed / 'docs/decisions.md').write_text(MAIN + '## 3. 병합됨\n', encoding='utf-8')
            git('commit', '-q', '-am', 'three', cwd=seed)
            git('push', '-q', 'origin', 'main', cwd=seed)
            prs = temp / 'prs.json'
            prs.write_text('[]', encoding='utf-8')
            command = [sys.executable, '-I', '-B', str(SCRIPT), '--root', str(temp / 'work'), '--prs-json', str(prs)]
            stale = subprocess.run(command + ['--no-fetch'], stdout=subprocess.PIPE, universal_newlines=True)
            fresh = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        self.assertEqual('3', stale.stdout.splitlines()[0])
        self.assertEqual(0, fresh.returncode, fresh.stderr)
        self.assertEqual('4', fresh.stdout.splitlines()[0])

    def test_fetches_main_when_local_ref_is_stale(self):
        # 리뷰 R1
        self.run_on_stale_clone()

    def test_fetches_main_even_when_refspec_excludes_it(self):
        # 리뷰 R3: 설정된 fetch refspec이 main을 포함하지 않아도 origin/main을 갱신해 읽는다.
        self.run_on_stale_clone('+refs/heads/other:refs/remotes/origin/other')

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
