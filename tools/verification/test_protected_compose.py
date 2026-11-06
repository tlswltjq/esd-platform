import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('protected_compose', ROOT / 'scripts/protected-compose.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ExternalInjectionTest(unittest.TestCase):
    def environment(self):
        return dict(PATH=os.environ['PATH'],
                    **{name: 'fixture-only' for name in MODULE.REQUIRED})

    def test_missing_each_required_key_is_rejected(self):
        for name in MODULE.REQUIRED:
            with self.subTest(name=name):
                environment = self.environment()
                del environment[name]
                with self.assertRaises(ValueError):
                    MODULE.deployment_environment(environment)

    def test_blank_keys_are_rejected(self):
        environment = self.environment()
        environment['BFF_CLIENT_SECRET'] = '  '
        with self.assertRaises(ValueError):
            MODULE.deployment_environment(environment)

    def test_injection_preserves_values_without_mutation(self):
        environment = self.environment()
        selected = MODULE.deployment_environment(environment)
        self.assertEqual(environment, selected)
        self.assertIsNot(environment, selected)

    def test_compose_control_overrides_are_rejected(self):
        environment = self.environment()
        environment['COMPOSE_FILE'] = 'unexpected.yml'
        with self.assertRaises(ValueError):
            MODULE.deployment_environment(environment)

    def test_enabled_catalog_storage_requires_its_credentials(self):
        environment = self.environment()
        environment['CATALOG_DOWNLOAD_STORAGE_ENABLED'] = 'true'
        with self.assertRaises(ValueError):
            MODULE.deployment_environment(environment)

    def test_compose_disables_dotenv_and_has_no_secret_arguments(self):
        command = MODULE.compose_command('fixture-apps', ROOT)
        self.assertEqual('/dev/null', command[command.index('--env-file') + 1])
        self.assertNotIn('fixture-only', ' '.join(command))

    def run_main(self, apply=False):
        arguments = ['protected-compose.py', '--project-name', 'fixture-apps']
        if apply:
            arguments.append('--apply')
        with patch.object(MODULE.sys, 'argv', arguments):
            with patch.dict(MODULE.os.environ, self.environment(), clear=True):
                return MODULE.main()

    def test_default_only_validates_and_suppresses_output(self):
        with patch.object(MODULE.subprocess, 'run') as run:
            run.return_value.returncode = 0
            self.assertEqual(0, self.run_main())
            self.assertEqual(1, run.call_count)
            self.assertEqual(['config', '--quiet'], run.call_args.args[0][-2:])
            self.assertEqual(MODULE.subprocess.DEVNULL, run.call_args.kwargs['stdout'])
            self.assertEqual(MODULE.subprocess.DEVNULL, run.call_args.kwargs['stderr'])

    def test_failed_validation_never_starts_apps(self):
        with patch.object(MODULE.subprocess, 'run') as run:
            run.return_value.returncode = 1
            self.assertEqual(1, self.run_main(apply=True))
            self.assertEqual(1, run.call_count)

    def test_apply_uses_existing_ordered_startup_with_same_injection(self):
        with patch.object(MODULE.subprocess, 'run') as run:
            run.return_value.returncode = 0
            self.assertEqual(0, self.run_main(apply=True))
            self.assertEqual(2, run.call_count)
            self.assertEqual(['bash', str(ROOT / 'scripts/start-apps.sh')],
                             run.call_args.args[0][:2])
            self.assertEqual(self.environment(), run.call_args.kwargs['env'])

    def test_actual_compose_check_uses_only_fixture_injection(self):
        result = subprocess.run(
            [os.sys.executable, '-B', str(ROOT / 'scripts/protected-compose.py'),
             '--project-name', 'g5-injection-fixture'],
            env=self.environment(), capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('fixture-only', result.stdout + result.stderr)

    def test_actual_compose_keeps_external_values_and_recipients(self):
        environment = self.environment()
        environment.update({name: 'fixture-$literal value-' + name
                            for name in MODULE.REQUIRED})
        result = subprocess.run(
            MODULE.compose_command('g5-injection-fixture', ROOT) + ['config', '--format', 'json'],
            env=environment, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode)
        services = json.loads(result.stdout)['services']
        for app in ('auth', 'studio', 'review', 'catalog', 'store', 'order',
                    'payment', 'license', 'settlement'):
            # Compose serializes literal dollars as $$ in reusable config output.
            self.assertEqual(environment['DB_PASSWORD'].replace('$', '$$'),
                             services[app]['environment']['DB_PASSWORD'])
        self.assertNotIn('DB_PASSWORD', services['gateway']['environment'])
        self.assertNotIn('DB_PASSWORD', services['download']['environment'])
        self.assertEqual(environment['STORAGE_SECRET_KEY'].replace('$', '$$'),
                         services['studio']['environment']['STORAGE_SECRET_KEY'])
        for name, expected in {
                'CATALOG_ORDER_SERVICE_KEY': {'catalog', 'order'},
                'PAYMENT_ORDER_SERVICE_KEY': {'payment', 'order'},
                'BFF_CLIENT_SECRET': {'auth', 'gateway'},
                'MOCK_PG_CONTROL_KEY': {'mock-pg'}}.items():
            self.assertEqual(expected, {app for app, config in services.items()
                                       if name in config.get('environment', {})})


if __name__ == '__main__':
    unittest.main()
