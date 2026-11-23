import argparse
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

import measure_guard


def state(containers=None, volumes=(), networks=()):
    return {'containers': containers or {}, 'volumes': list(volumes), 'networks': list(networks)}


class UnrelatedRunningTest(unittest.TestCase):
    def test_only_running_and_not_allowed(self):
        current = state({'kirothon-app-1': 'running', 'messaging-measure-kafka-1': 'running', 'old': 'exited'})
        self.assertEqual(['kirothon-app-1'], measure_guard.unrelated_running(current, ['messaging-measure-']))


class CompareTest(unittest.TestCase):
    def test_missing_and_leftover(self):
        before = state({'keep': 'running', 'gone': 'exited'}, volumes=['v1'], networks=['n1'])
        after = state({'keep': 'exited', 'new': 'running'}, volumes=['v1', 'v2'], networks=['n1'])
        missing, leftover = measure_guard.compare(before, after)
        self.assertEqual({'containers': ['gone']}, missing)
        self.assertEqual({'containers': ['new'], 'volumes': ['v2']}, leftover)

    def test_same(self):
        same = state({'a': 'running'}, volumes=['v'])
        self.assertEqual(({}, {}), measure_guard.compare(same, same))


class CommandTest(unittest.TestCase):
    def test_before_fails_on_unrelated_running_but_records_state(self):
        # S6를 kirothon-app-1이 켜진 채 재서 다시 잰 사례.
        with tempfile.TemporaryDirectory() as root, \
                mock.patch.object(measure_guard, 'snapshot', return_value=state({'kirothon-app-1': 'running'})):
            args = argparse.Namespace(root=root, name='s6', allow=[], ignore_running=False)
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, measure_guard.before(args))
                self.assertEqual(0, measure_guard.before(argparse.Namespace(**dict(vars(args), ignore_running=True))))
            saved = json.loads((Path(root) / 'build/measure-guard/s6.json').read_text(encoding='utf-8'))
            self.assertEqual({'kirothon-app-1': 'running'}, saved['containers'])

    def test_after_reports_leftovers(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'build/measure-guard/s7.json'
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(state({'a': 'running'})), encoding='utf-8')
            out = io.StringIO()
            with mock.patch.object(measure_guard, 'snapshot', return_value=state({'a': 'running', 'b': 'exited'})), \
                    redirect_stdout(out):
                self.assertEqual(1, measure_guard.after(argparse.Namespace(root=root, name='s7')))
            self.assertIn('정리 누락', out.getvalue())

    def test_name_cannot_escape_the_state_directory(self):
        with self.assertRaises(SystemExit):
            measure_guard.state_path('/tmp', '../x')


if __name__ == '__main__':
    unittest.main()
