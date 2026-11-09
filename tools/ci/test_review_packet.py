from pathlib import Path
import subprocess
import tempfile
import unittest

import review_packet

DECISIONS = ('# 결정\n\n<a id="one"></a>\n## 1. 첫째\n- 문제: 하나\n\n<a id="ten"></a>\n## 10. 열째\n- 문제: 열\n\n'
             '## 100. 백째\n- 문제: 백\n\n---\n\n## 검증하며 드러난 것\n끝\n')
AGENTS = '# 작업 기준\n\n## 2. 문서 규칙\n문서\n\n## 3. 코드 규칙\n\n- 명시적 타입\n\n## 4. 모듈 책임\n모듈\n'
REQUIREMENTS = ('# 요구사항\n\n### R1. 접수\n#### 수용 기준\n1. `Receiver`가 받는다\n\n'
                '### R2. 조회\n1. 읽기\n\n### R12. 보류\n1. `Holder`가 멈춘다\n\n## 범위 밖\n없음\n')


class SectionTest(unittest.TestCase):
    def test_stops_at_same_or_higher_heading(self):
        self.assertEqual('## 3. 코드 규칙\n\n- 명시적 타입\n', review_packet.section(AGENTS, '## 3. '))
        self.assertIn('#### 수용 기준', review_packet.section(REQUIREMENTS, '### R1. '))
        self.assertEqual('## 1. 첫째\n- 문제: 하나\n', review_packet.section(DECISIONS, '## 1. '))
        self.assertNotIn('R2', review_packet.section(REQUIREMENTS, '### R1. '))
        self.assertEqual('', review_packet.section(AGENTS, '## 9. '))

    def test_decision_prefix_does_not_match_longer_numbers(self):
        self.assertEqual(['<a id="ten"></a>\n## 10. 열째\n- 문제: 열\n'], review_packet.decision_sections(DECISIONS, [10]))
        self.assertEqual(['## 100. 백째\n- 문제: 백\n'], review_packet.decision_sections(DECISIONS, [100]))
        self.assertEqual([], review_packet.decision_sections(DECISIONS, [7]))


class ReferenceTest(unittest.TestCase):
    def test_decision_numbers_split_joined_lists(self):
        self.assertEqual([110, 111, 122, 128, 140],
                         review_packet.decision_numbers('결정 110·111 과 결정 128·122', '[결정 140](x)'))
        self.assertEqual([], review_packet.decision_numbers('', None))

    def test_requirement_numbers_take_the_top_level(self):
        self.assertEqual({8, 12, 13}, review_packet.requirement_numbers('R12.10, R8-3', 'R13 절'))
        self.assertEqual(set(), review_packet.requirement_numbers('ORDER_R1 PR #12 R2D2'))
        # 리뷰 2: 한국어 조사가 붙어도 잡는다.
        self.assertEqual({1, 6}, review_packet.requirement_numbers('R1의 공개 상태, R6-5를 지킨다'))

    def test_modules_and_changed_classes(self):
        files = ['apps/payment/src/main/java/a/Holder.java', 'apps/payment/src/test/java/a/HolderTest.java',
                 '.kiro/specs/license/design.md', 'docs/decisions.md', 'tools/ci/x.py']
        self.assertEqual(['license', 'payment'], review_packet.modules(files))
        self.assertEqual(['Holder'], review_packet.changed_classes(files))

    def test_requirement_sections_full_by_number_and_titles_by_class(self):
        full, titles = review_packet.requirement_sections(REQUIREMENTS, {2}, ['Holder'])
        self.assertEqual(['### R2. 조회'], [p.split('\n')[0] for p in full])
        self.assertEqual(['### R12. 보류'], titles)

    def test_numbers_come_from_changed_lines_and_uncommented_body(self):
        diff = 'diff --git a/x b/x\n--- a/x\n+++ b/x\n@@\n 문맥 결정 7\n+추가 결정 8\n-삭제 결정 9\n'
        self.assertEqual([8, 9], review_packet.decision_numbers(review_packet.changed_lines(diff)))
        self.assertEqual([], review_packet.decision_numbers(review_packet.without_comments('<!-- 결정 137 -->')))

    def test_own_decisions_are_the_body_section_and_commit_subjects(self):
        body = '## 맡은 항목\n- 결정 번호: 5\n\n## 이번 PR의 결정\n\n- [결정 6](x) — 선택\n\n## 계획과 구현\n결정 7 참고\n'
        self.assertEqual([6, 9], review_packet.own_decisions(body, 'fix: 무엇 (결정 9)\n'))

    def test_diff_order_puts_production_code_first_and_docs_last(self):
        files = ['docs/a.md', 'apps/x/src/test/java/T.java', 'tools/ci/test_y.py', 'apps/x/src/main/java/M.java',
                 'apps/x/src/main/resources/db/migration/V2__a.sql', 'infra/a.yml']
        self.assertEqual(['apps/x/src/main/java/M.java', 'apps/x/src/main/resources/db/migration/V2__a.sql',
                          'apps/x/src/test/java/T.java', 'tools/ci/test_y.py', 'infra/a.yml', 'docs/a.md'],
                         sorted(files, key=lambda f: (review_packet.group(f), f)))


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.git('init', '-q')
        self.git('symbolic-ref', 'HEAD', 'refs/heads/main')
        self.git('config', 'user.email', 't@example.com')
        self.git('config', 'user.name', 't')
        self.write('AGENTS.md', AGENTS)
        self.write('docs/decisions.md', DECISIONS)
        self.write('.kiro/specs/payment/requirements.md', REQUIREMENTS)
        self.write('apps/payment/src/main/java/a/Holder.java', 'class Holder {}\n')
        self.commit('base')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-q', '-m', message)

    def test_packet_order_and_contents(self):
        self.write('apps/payment/src/main/java/a/Holder.java', 'class Holder { int x; }\n')
        self.write('docs/note.md', '메모\n')
        self.commit('fix: 보류 (결정 10)')
        packet, files, own = review_packet.build(self.root, self.base, 'HEAD', pr_body='결정 1 · R2 <!-- 결정 100 -->')
        self.assertEqual([10], own)
        self.assertEqual(['apps/payment/src/main/java/a/Holder.java', 'docs/note.md'], sorted(files))
        order = [packet.index(s) for s in ['# 리뷰 묶음', '## 3. 코드 규칙', '## 10. 열째', '- 1. 첫째',
                                           '### R2. 조회', '- ### R12. 보류', 'Holder.java', 'docs/note.md']]
        self.assertEqual(sorted(order), order)
        self.assertNotIn('## 1. 첫째', packet)
        self.assertNotIn('백째', packet)
        self.assertNotIn('1. `Holder`가 멈춘다', packet)
        self.assertNotIn('### R1. 접수', packet)
        self.assertNotIn('## 4. 모듈 책임', packet)

    def test_rereview_only_carries_the_new_commits_and_previous_findings(self):
        self.write('apps/payment/src/main/java/a/Holder.java', 'class Holder { int x; }\n')
        self.commit('first')
        reviewed = self.git('rev-parse', 'HEAD').strip()
        self.write('apps/payment/src/main/java/a/Other.java', 'class Other {}\n')
        self.commit('second')
        packet, files, _ = review_packet.build(self.root, self.base, 'HEAD', since=reviewed, previous='R1 high x')
        self.assertEqual(['apps/payment/src/main/java/a/Other.java'], files)
        self.assertIn('## 이전 리뷰 지적', packet)
        self.assertIn('R1 high x', packet)
        self.assertNotIn('int x', packet)

    def test_a_rename_keeps_both_paths_in_one_block(self):
        # 리뷰 1: 이름 변경이 새 파일 추가로 바뀌어 옛 경로 삭제가 빠지지 않는다.
        self.git('mv', 'apps/payment/src/main/java/a/Holder.java', 'apps/payment/src/main/java/a/Keeper.java')
        self.commit('rename')
        packet, files, _ = review_packet.build(self.root, self.base, 'HEAD')
        self.assertEqual(['apps/payment/src/main/java/a/Keeper.java'], files)
        self.assertIn('rename from apps/payment/src/main/java/a/Holder.java', packet)
        self.assertNotIn('new file mode', packet)

    def test_reference_documents_come_from_the_reviewed_revision(self):
        # 리뷰 3: --head로 다른 판을 리뷰하면 결정·AGENTS·요구사항도 그 판에서 읽는다.
        self.write('docs/decisions.md', DECISIONS.replace('## 100. 백째', '## 7. 일곱째\n- 문제: 칠\n\n## 100. 백째'))
        self.write('apps/payment/src/main/java/a/Holder.java', 'class Holder { int z; }\n')
        self.commit('fix: 일곱 (결정 7)')
        reviewed = self.git('rev-parse', 'HEAD').strip()
        self.write('docs/decisions.md', DECISIONS)
        packet, _, own = review_packet.build(self.root, self.base, reviewed)
        self.assertEqual([7], own)
        self.assertIn('## 7. 일곱째', packet)

    def test_main_writes_the_packet_and_requires_since_for_previous(self):
        out = self.root / 'packet.md'
        self.write('apps/payment/src/main/java/a/Holder.java', 'class Holder { int y; }\n')
        self.commit('change')
        self.assertEqual(0, review_packet.main(['--root', str(self.root), '--base', self.base, '--out', str(out)]))
        self.assertTrue(out.read_text(encoding='utf-8').startswith('# 리뷰 묶음'))
        with self.assertRaises(SystemExit):
            review_packet.main(['--root', str(self.root), '--previous', str(out), '--out', str(out)])


if __name__ == '__main__':
    unittest.main()
