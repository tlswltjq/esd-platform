from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import decision_numbers

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/ci/decision_numbers.py'
VALID = '<a id="one"></a>\n## 1. 첫째\n<a id="two"></a>\n## 2. 둘째\n'


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write('docs/decisions.md', VALID)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def errors(self):
        return '\n'.join(decision_numbers.check(self.root)[0])


class DecisionNumbersTest(Fixture):
    def test_valid(self):
        self.write('.github/example.md', '[결정 2](../docs/decisions.md#two)')
        errors, counts = decision_numbers.check(self.root)
        self.assertEqual(errors, [])
        self.assertEqual(counts, (2, 2, 2, 1))

    def test_duplicate_number(self):
        self.write('docs/decisions.md', VALID + '## 2. 중복\n')
        self.assertIn('중복 번호 2', self.errors())

    def test_gap_reserved_by_open_pr_is_allowed(self):
        # 결정 139: 열린 PR이 예약한 2가 아직 main에 없어도 3을 병합할 수 있다.
        self.write('docs/decisions.md', VALID.replace('## 2.', '## 3.'))
        self.assertEqual(decision_numbers.check(self.root)[0], [])

    def test_reverse_number(self):
        self.write('docs/decisions.md', '## 1. A\n## 3. C\n## 2. B\n')
        error = self.errors()
        self.assertIn('역순 번호 2 (앞 번호 3)', error)
        self.assertIn('번호 자리에 둔다', error)

    def test_sequence_starts_at_one(self):
        self.write('docs/decisions.md', '## 0. 잘못된 시작\n## 1. 첫째\n')
        self.assertIn('순서 오류 번호 0 (기대 1 이상)', self.errors())

    def test_new_number_not_in_base_is_rejected(self):
        # 결정 decision-name-ids: 새 결정에는 번호를 쓰지 않는다. 빈 번호 자리에 끼워 넣는 것도 막는다.
        base = '## 1. A\n## 3. C\n'
        self.write('docs/decisions.md', '## 1. A\n## 2. B\n## 3. C\n')
        error = '\n'.join(decision_numbers.check(self.root, base_text=base)[0])
        self.assertIn('새 번호 2', error)
        self.assertIn('decision-name-ids', error)

    def test_number_after_the_last_numbered_decision_is_rejected(self):
        last = decision_numbers.LAST_NUMBER
        self.write('docs/decisions.md', VALID + '## %d. 마지막\n' % last)
        self.assertEqual(self.errors(), '')
        self.write('docs/decisions.md', VALID + '## %d. 넘음\n' % (last + 1))
        self.assertIn('번호 결정은 %d에서 끝났다' % last, self.errors())

    def test_bound_ignores_numbers_already_in_base(self):
        base = VALID + '## 20. 이미 main에 있음\n'
        self.write('docs/decisions.md', base)
        self.assertEqual(decision_numbers.check(self.root, base_text=base)[0], [])

    def test_duplicate_anchor(self):
        self.write('docs/decisions.md', VALID + '<a id="one"></a>\n')
        self.assertIn('중복 앵커 one', self.errors())

    def test_other_file_mismatch(self):
        self.write('.github/example.md', '\n[결정 1](../docs/decisions.md#two)')
        error = self.errors()
        self.assertIn('.github/example.md:2', error)
        self.assertIn('[결정 1](../docs/decisions.md#two)', error)
        self.assertIn('실제 번호 2', error)

    def test_local_mismatch(self):
        self.write('docs/decisions.md', VALID + '[결정 1](#two)')
        self.assertIn('실제 번호 2', self.errors())

    def test_automatic_anchor_number_is_checked(self):
        self.write('README.md', '[결정 99](docs/decisions.md#2-둘째)')
        self.assertIn('실제 번호 2', self.errors())

    def test_missing_automatic_anchor_fails(self):
        # PR #233 R3: 숫자로 시작하는 앵커라도 실제 헤딩이 없으면 그 숫자로 간주하지 않는다.
        self.write('README.md', '[결정 999](docs/decisions.md#999-missing)')
        self.assertIn('없는 앵커 999-missing', self.errors())

    def test_automatic_anchor_slug_must_match_heading(self):
        self.write('README.md', '[결정 2](docs/decisions.md#2-셋째)')
        self.assertIn('없는 앵커 2-셋째', self.errors())

    def test_real_heading_automatic_anchor_passes(self):
        self.write('docs/decisions.md', VALID + '## 3. `@Foo`는 [링크](x.md)와 A.B, 둘_째를 쓴다\n')
        self.write('README.md', '[결정 3](docs/decisions.md#3-foo는-링크와-ab-둘_째를-쓴다)')
        self.assertEqual(self.errors(), '')

    def test_duplicate_automatic_anchor_gets_suffix(self):
        self.write('docs/decisions.md', '# 1. 같다\n## 1. 같다\n')
        self.write('README.md', '[결정 1](docs/decisions.md#1-같다-1)')
        self.assertEqual(self.errors(), '')

    def test_r1_suffix_skips_an_anchor_already_taken(self):
        # PR #233 재리뷰 R1: `같다-1` 헤딩이 먼저 있으면 두 번째 `같다`는 -1이 아니라 -2다(github-slugger).
        self.write('docs/decisions.md', '# 1. 같다-1\n# 1. 같다\n## 1. 같다\n')
        self.write('README.md', '[결정 1](docs/decisions.md#1-같다-2)')
        self.assertEqual(self.errors(), '')
        self.write('README.md', '[결정 1](docs/decisions.md#1-같다-1)')
        self.assertIn('없는 앵커 1-같다-1', self.errors())

    def test_r2_slug_uses_the_rendered_text(self):
        # PR #233 재리뷰 R2: 강조 문법은 벗기고, 인라인 코드 안의 링크 문법은 글자 그대로 둔다.
        self.write('docs/decisions.md', VALID + '## 3. _강조_ **굵게**\n## 4. `[x](y)` 예\n')
        self.write('README.md', '[결정 3](docs/decisions.md#3-강조-굵게) [결정 4](docs/decisions.md#4-xy-예)')
        self.assertEqual(self.errors(), '')
        self.assertEqual(decision_numbers.slug('snake_case 이름'), 'snake_case-이름')
        # GFM: 닫는 `_` 바로 뒤에 글자가 붙으면 강조가 아니다.
        self.assertEqual(decision_numbers.slug('_강조_와'), '_강조_와')
        # 재리뷰: 강조가 코드를 감싸도 벗기고, 단어 안의 `__`는 강조가 아니다.
        self.assertEqual(decision_numbers.slug('3. _a `x` b_'), '3-a-x-b')
        self.assertEqual(decision_numbers.slug('3. snake__case__name'), '3-snake__case__name')
        self.assertEqual(decision_numbers.slug('3. **굵게** `a*b*c`'), '3-굵게-abc')

    def test_fences_comments_and_inline_code_are_ignored(self):
        self.write('docs/decisions.md', VALID + '\n```md\n## 99. 예\n```\n~~~\n## 88. 예\n~~~\n<!-- 예시 -->\n`[결정 1](#two)`\n')
        self.assertEqual(decision_numbers.check(self.root), ([], (2, 2, 2, 0)))

    def test_excluded_directories(self):
        for folder in ('build', 'node_modules', '.git', 'nested/build'):
            self.write(folder + '/example.md', '[결정 1](../docs/decisions.md#two)')
        self.assertEqual(self.errors(), '')

    def test_cli_success(self):
        result = self.cli()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('통과: 헤딩 2개, 최대 번호 2, 앵커 2개, 검사한 링크 0개', result.stdout)

    def test_cli_failure(self):
        self.write('docs/decisions.md', VALID + '## 2. 중복\n')
        result = self.cli()
        self.assertEqual(result.returncode, 1)
        self.assertIn('중복 번호 2', result.stdout)

    def cli(self):
        return subprocess.run([sys.executable, '-I', '-B', str(SCRIPT), '--root', str(self.root)],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)


NAMED = VALID + '<a id="spec-root"></a>\n## 스펙은 루트 `specs/`에 둔다\n- 문제: x\n'


class NamedDecisionTest(Fixture):
    def test_named_decision_and_matching_link(self):
        self.write('docs/decisions.md', NAMED + '[결정 spec-root](#spec-root)\n')
        self.write('README.md', '[결정 spec-root](docs/decisions.md#spec-root) [결정 기록](docs/decisions.md#x)')
        errors, counts = decision_numbers.check(self.root)
        self.assertEqual(errors, [])
        self.assertEqual(counts, (3, 2, 3, 2))

    def test_label_must_equal_anchor(self):
        self.write('docs/decisions.md', NAMED + '<a id="other-id"></a>\n## 다른 결정\n')
        self.write('README.md', '[결정 spec-root](docs/decisions.md#other-id)')
        self.assertIn('식별자 spec-root와 앵커 other-id가 다르다', self.errors())

    def test_id_label_on_numbered_anchor_fails(self):
        self.write('docs/decisions.md', NAMED)
        self.write('README.md', '[결정 one](docs/decisions.md#one)')
        self.assertIn('식별자 one와 앵커 one가 다르다', self.errors())

    def test_number_label_on_named_anchor_fails(self):
        self.write('docs/decisions.md', NAMED)
        self.write('README.md', '[결정 3](docs/decisions.md#spec-root)')
        self.assertIn('실제 식별자 spec-root', self.errors())

    def test_missing_named_anchor_fails(self):
        self.write('docs/decisions.md', NAMED)
        self.write('README.md', '[결정 spec-rooot](docs/decisions.md#spec-rooot)')
        self.assertIn('없는 앵커 spec-rooot', self.errors())

    def test_named_decision_needs_exactly_one_kebab_anchor(self):
        self.write('docs/decisions.md', VALID + '<a id="a-b"></a><a id="c-d"></a>\n## 둘\n')
        self.assertIn('식별자 앵커는 하나', self.errors())
        for bad in ('Spec_Root', '12', 'x--y', '-x'):
            self.write('docs/decisions.md', VALID + '<a id="%s"></a>\n## 형식\n' % bad)
            self.assertIn('식별자 형식', self.errors(), bad)

    def test_r2_named_heading_must_follow_its_anchor_line(self):
        # PR #259 R2: 리뷰 묶음과 같은 형식 — 식별자 앵커 바로 다음 줄이 제목이다.
        self.write('docs/decisions.md', VALID + '<a id="spec-root"></a>\n\n## 스펙 위치\n')
        self.assertIn('앵커 바로 다음 줄', self.errors())

    def test_heading_without_anchor_is_not_a_decision(self):
        self.write('docs/decisions.md', NAMED + '\n---\n\n## 검증하며 드러난 것\n')
        self.assertEqual(decision_numbers.check(self.root)[1][0], 3)

    def test_named_decision_anchor_is_preserved_against_base(self):
        self.write('docs/decisions.md', NAMED.replace('spec-root', 'spec-home'))
        self.assertIn('기준 판', '\n'.join(decision_numbers.check(self.root, base_text=NAMED)[0]))
        self.write('docs/decisions.md', NAMED)
        self.assertEqual(decision_numbers.check(self.root, base_text=NAMED)[0], [])


class RepositoryTest(unittest.TestCase):
    def test_repository_passes(self):
        errors, counts = decision_numbers.check(ROOT)
        self.assertEqual(errors, [])
        self.assertGreaterEqual(counts[0], 114)


class ReviewFindingsTest(Fixture):
    def test_missing_explicit_anchor(self):
        self.write('README.md', '[결정 2](docs/decisions.md#gone)')
        self.assertIn('없는 앵커 gone', self.errors())

    def test_compound_labels(self):
        for label in ('1·2', '1~2', '1, 2'):
            self.write('README.md', '[결정 ' + label + '](docs/decisions.md#two)')
            self.assertEqual(decision_numbers.check(self.root), ([], (2, 2, 2, 1)))
        self.write('README.md', '[결정 3~5](docs/decisions.md#two)')
        self.assertIn('실제 번호 2', self.errors())

    def test_base_numbers_and_anchors_are_immutable(self):
        self.write('docs/decisions.md', VALID.replace('## 2.', '## 3.'))
        self.assertIn('기준 판', '\n'.join(decision_numbers.check(self.root, base_text=VALID)[0]))
        self.write('docs/decisions.md', VALID.replace('id="two"', 'id="gone"'))
        self.assertIn('기준 판', '\n'.join(decision_numbers.check(self.root, base_text=VALID)[0]))

    def test_duplicate_diagnostic_and_local_sequence(self):
        self.write('docs/decisions.md', VALID + '<a id="three"></a>\n## 2. 충돌\n## 3. 다음\n')
        error = self.errors()
        self.assertIn('two', error)
        self.assertIn('three', error)
        self.assertIn('나중에 병합하는 쪽', error)
        # 결정 139: 중복은 중복으로만 보고하고, 뒤의 3은 앞 번호 2보다 커서 오류가 아니다.
        self.assertEqual(error.count('중복 번호 2'), 1)
        self.assertEqual(error.count('순서 오류') + error.count('역순'), 0)

    def test_unpaired_backticks_do_not_hide_headings(self):
        self.write('docs/decisions.md', '`열림\n' + VALID + '`닫힘\n')
        self.assertEqual(self.errors(), '')

    def test_hidden_raw_heading_is_an_error(self):
        self.write('docs/decisions.md', VALID + '<!--\n## 99. 숨김\n-->')
        self.assertIn('헤딩이 지워짐', self.errors())

    def test_unclosed_inline_comment_does_not_eat_file(self):
        self.write('docs/decisions.md', '문장 <!-- 닫힘 없음\n' + VALID)
        self.assertEqual(self.errors(), '')

    def test_unclosed_block_comment_has_safety_net(self):
        self.write('docs/decisions.md', VALID + '<!--\n## 99. 숨김\n')
        self.assertIn('헤딩이 지워짐', self.errors())

    def test_comment_fence_does_not_open_code(self):
        self.write('docs/decisions.md', '<!--\n```\n-->\n' + VALID)
        self.assertEqual(decision_numbers.check(self.root)[1][0], 2)

    def test_malformed_headings(self):
        for heading in ('## 2 중복', '## 2.중복', '##\t2. 중복', '## 2.'):
            self.write('docs/decisions.md', VALID + heading)
            self.assertIn('헤딩 형식', self.errors())

    def test_multiple_and_trailing_anchors(self):
        self.write('docs/decisions.md', '<a id="one"></a><a id="alias"></a>\n## 1. 첫째\n## 2. 둘째 <a id="two"></a>\n[결정 1](#alias) [결정 2](#two)')
        self.assertEqual(decision_numbers.check(self.root), ([], (2, 2, 3, 2)))

    def test_anchor_body_resets_association(self):
        self.write('docs/decisions.md', '<a id="stray"></a>\n본문\n' + VALID + '[결정 1](#stray)')
        self.assertIn('없는 앵커 stray', self.errors())

    def test_non_decision_target_is_ignored(self):
        self.write('README.md', '[결정 99](other.md#two)')
        self.assertEqual(self.errors(), '')

    def test_other_document_local_fragment_is_ignored(self):
        self.write('README.md', '[결정 99](#two)')
        self.assertEqual(self.errors(), '')

    def test_fence_close_guards(self):
        for wrong in ('```', '~~~~', '````info'):
            self.write('docs/decisions.md', VALID + '\n````md\n' + wrong + '\n## 99. 코드\n````\n')
            self.assertEqual(decision_numbers.check(self.root)[1][0], 2)
        self.write('docs/decisions.md', '```md\n예\n```\n' + VALID)
        self.assertEqual(decision_numbers.check(self.root)[1][0], 2)

    def test_heading_indentation(self):
        for indent in range(4):
            self.write('docs/decisions.md', ' ' * indent + '## 1. 제목\n')
            self.assertEqual(decision_numbers.check(self.root)[1][0], 1)
        self.write('docs/decisions.md', VALID + '    ## 99. 코드\n')
        self.assertEqual(decision_numbers.check(self.root)[1][0], 2)

    def test_symlink_is_excluded(self):
        self.write('bad.txt', '[결정 99](docs/decisions.md#two)')
        (self.root / 'link.md').symlink_to(self.root / 'bad.txt')
        self.assertEqual(self.errors(), '')

    def test_git_tracked_build_and_untracked_file(self):
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.write('build/tracked.md', '[결정 99](../docs/decisions.md#two)')
        self.write('untracked.md', '[결정 99](docs/decisions.md#one)')
        subprocess.run(['git', '-C', str(self.root), 'add', '-f', 'docs/decisions.md', 'build/tracked.md'], check=True)
        error = self.errors()
        self.assertIn('build/tracked.md', error)
        self.assertNotIn('untracked.md', error)

    def test_r2_a_missing_base_fails_instead_of_passing(self):
        """PR #232 R2: 보존 검사를 요청했는데 기준 판을 못 읽으면 실패한다. 기준 판 없는 검사는 --base-ref 없이 돈다."""
        result = subprocess.run([sys.executable, '-I', '-B', str(SCRIPT), '--root', str(self.root), '--base-ref', 'missing'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('기준 판 missing', result.stdout)
        result = subprocess.run([sys.executable, '-I', '-B', str(SCRIPT), '--root', str(self.root)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        self.assertEqual(result.returncode, 0)


class DecisionWorkflowTest(unittest.TestCase):
    def test_review_gate_contract(self):
        text = (ROOT / '.github/workflows/review-gate.yml').read_text()
        self.assertIn('fetch-depth: 2', text)
        self.assertIn("-p 'test_*.py'", text)
        block = text.split('- name: Check decision numbers')[1].split('- name:')[0]
        self.assertIn('if: ${{ !cancelled() }}', block)
        self.assertIn('--base-ref HEAD^1', block)

    def test_build_contract(self):
        text = (ROOT / '.github/workflows/ci.yml').read_text().split('\n  stack:')[0]
        self.assertIn('fetch-depth: 2', text)
        # PR #233 R7: 번호 오류를 Gradle 빌드 전에 알린다.
        self.assertLess(text.index('- name: Check decision numbers'), text.index('- name: Build scope'))
        self.assertLess(text.index('- name: Check decision numbers'), text.index('- name: Build\n'))
        block = text.split('- name: Check decision numbers')[1].split('- name:')[0]
        self.assertIn('if: ${{ !cancelled() }}', block)
        self.assertIn('--base-ref HEAD^1', block)


if __name__ == '__main__':
    unittest.main()
