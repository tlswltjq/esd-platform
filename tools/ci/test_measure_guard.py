import argparse
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

import measure_guard


def container(state, identifier='c1'):
    return {'id': identifier, 'state': state}


def state(containers=None, volumes=(), networks=None):
    return {'containers': containers or {}, 'volumes': {name: {} for name in volumes}, 'networks': networks or {}}


class UnrelatedRunningTest(unittest.TestCase):
    def test_only_running_and_not_allowed(self):
        current = state({'kirothon-app-1': container('running'), 'messaging-measure-kafka-1': container('running'),
                         'old': container('exited')})
        self.assertEqual(['kirothon-app-1'], measure_guard.unrelated_running(current, ['messaging-measure-']))


class CompareTest(unittest.TestCase):
    def test_missing_and_leftover(self):
        before = state({'keep': container('running'), 'gone': container('exited', 'c2')}, volumes=['v1'],
                       networks={'n1': {'id': 'n'}})
        after = state({'keep': container('running'), 'new': container('running', 'c3')}, volumes=['v1', 'v2'],
                      networks={'n1': {'id': 'n'}})
        missing, leftover, changed = measure_guard.compare(before, after)
        self.assertEqual({'containers': ['gone']}, missing)
        self.assertEqual({'containers': ['new'], 'volumes': ['v2']}, leftover)
        self.assertEqual({}, changed)

    def test_state_change_and_recreation_are_reported(self):
        # PR #226 R4: 이름만 같으면 통과하던 경우.
        before = state({'stopped': container('running', 'a'), 'recreated': container('running', 'b')},
                       networks={'net': {'id': 'x'}})
        after = state({'stopped': container('exited', 'a'), 'recreated': container('running', 'b2')},
                      networks={'net': {'id': 'y'}})
        _, _, changed = measure_guard.compare(before, after)
        self.assertEqual({'containers': ['recreated (다시 만들어짐)', 'stopped (running → exited)'],
                          'networks': ['net (다시 만들어짐)']}, changed)

    def test_same(self):
        same = state({'a': container('running')}, volumes=['v'])
        self.assertEqual(({}, {}, {}), measure_guard.compare(same, same))


class CommandTest(unittest.TestCase):
    def test_before_fails_on_unrelated_running_but_records_state(self):
        # S6를 kirothon-app-1이 켜진 채 재서 다시 잰 사례.
        with tempfile.TemporaryDirectory() as root, \
                mock.patch.object(measure_guard, 'snapshot', return_value=state({'kirothon-app-1': container('running')})):
            args = argparse.Namespace(root=root, name='s6', allow=[], ignore_running=False)
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, measure_guard.before(args))
                self.assertEqual(0, measure_guard.before(argparse.Namespace(**dict(vars(args), ignore_running=True))))
            saved = json.loads((Path(root) / 'build/measure-guard/s6.json').read_text(encoding='utf-8'))
            self.assertEqual({'kirothon-app-1': container('running')}, saved['containers'])

    def test_after_reports_leftovers_and_changes(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'build/measure-guard/s7.json'
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(state({'a': container('running')})), encoding='utf-8')
            out = io.StringIO()
            now = state({'a': container('exited'), 'b': container('exited', 'c9')})
            with mock.patch.object(measure_guard, 'snapshot', return_value=now), redirect_stdout(out):
                self.assertEqual(1, measure_guard.after(argparse.Namespace(root=root, name='s7')))
            self.assertIn('정리 누락', out.getvalue())
            self.assertIn('a (running → exited)', out.getvalue())

    def test_name_cannot_escape_the_state_directory(self):
        with self.assertRaises(SystemExit):
            measure_guard.state_path('/tmp', '../x')


if __name__ == '__main__':
    unittest.main()
