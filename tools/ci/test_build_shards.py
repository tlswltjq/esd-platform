"""결정 217: 통합·이행 테스트 나눔이 빠짐·겹침 없이 저장소 모듈과 맞는지 지킨다."""
from pathlib import Path
import io
import json
import re
import unittest
from contextlib import redirect_stdout

import build_shards

ROOT = Path(__file__).resolve().parents[2]


def project_path(directory):
    return ':' + directory.relative_to(ROOT).as_posix().replace('/', ':')


class RepositoryContractTest(unittest.TestCase):
    def test_every_integration_source_set_is_assigned_once(self):
        modules = sorted(project_path(source.parent.parent) for source in ROOT.glob('*/*/src/integrationTest')
                         if source.is_dir())
        self.assertTrue(modules)
        tasks = build_shards.assigned()
        self.assertEqual(len(tasks), len(set(tasks)), '같은 작업이 두 조각에 배정됐다')
        missing = [module for module in modules if module + ':integrationTest' not in tasks]
        self.assertEqual([], missing, '새 통합 테스트 모듈은 build_shards.INFRA_SHARDS에 배정한다')
        unknown = [task for task in tasks if task.rsplit(':', 1)[0] not in modules]
        self.assertEqual([], unknown, '없는 모듈의 작업이 배정됐다')

    def test_every_migration_test_module_is_assigned(self):
        text = (ROOT / 'build.gradle').read_text(encoding='utf-8')
        staged = re.search(r'stagedVerification = project\.path in \[([^\]]*)\]', text)
        self.assertIsNotNone(staged)
        modules = re.findall(r"'(:[^']+)'", staged.group(1))
        self.assertTrue(modules)
        for module in modules:
            self.assertIn(module + ':migrationTest', build_shards.assigned())


class PlanTest(unittest.TestCase):
    def test_full_build_splits_and_the_unit_shard_excludes_only_assigned_tasks(self):
        shards = build_shards.plan(['build'])
        self.assertEqual(['unit'] + [name for name, _ in build_shards.INFRA_SHARDS], [s['shard'] for s in shards])
        unit = shards[0]['tasks'].split()
        self.assertEqual('build', unit[0])
        excluded = [unit[i + 1] for i, word in enumerate(unit) if word == '-x']
        self.assertEqual(sorted(build_shards.assigned()), sorted(excluded))
        rest = [task for s in shards[1:] for task in s['tasks'].split()]
        self.assertEqual(sorted(build_shards.assigned()), sorted(rest))

    def test_scoped_build_is_one_shard_and_empty_means_no_gradle(self):
        scoped = [':apps:order:build', ':common:test:test']
        self.assertEqual([{'shard': 'scoped', 'tasks': ':apps:order:build :common:test:test'}], build_shards.plan(scoped))
        self.assertEqual([], build_shards.plan([]))

    def test_main_prints_github_outputs(self):
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(0, build_shards.main(['--tasks', '']))
        lines = out.getvalue().splitlines()
        self.assertEqual('gradle=false', lines[0])
        self.assertEqual({'include': []}, json.loads(lines[1].split('=', 1)[1]))
        out = io.StringIO()
        with redirect_stdout(out):
            build_shards.main(['--tasks', 'build'])
        lines = out.getvalue().splitlines()
        self.assertEqual('gradle=true', lines[0])
        self.assertEqual(1 + len(build_shards.INFRA_SHARDS), len(json.loads(lines[1].split('=', 1)[1])['include']))


if __name__ == '__main__':
    unittest.main()
