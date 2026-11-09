import unittest
import json
import os
from pathlib import Path
import subprocess

from support.service_credentials import credential_names, service_environment


class ServiceCredentialsTest(unittest.TestCase):
    def test_catalog_call_keys_only_reach_their_two_endpoints(self):
        expected = {
            'CATALOG_STORE_SERVICE_KEY': {'catalog', 'store'},
            'CATALOG_ORDER_SERVICE_KEY': {'catalog', 'order'},
            'CATALOG_DOWNLOAD_SERVICE_KEY': {'catalog', 'download'},
            'CATALOG_SETTLEMENT_SERVICE_KEY': {'catalog', 'settlement'},
            'PAYMENT_ORDER_SERVICE_KEY': {'payment', 'order'},
        }
        apps = ['auth', 'gateway', 'studio', 'review', 'catalog', 'store', 'order',
                'payment', 'license', 'download', 'settlement']
        inherited = {name: 'fixture-' + name.lower() for name in expected}
        for name, recipients in expected.items():
            actual = {app for app in apps if name in service_environment(app, inherited)}
            self.assertEqual(recipients, actual, name)

    def test_settlement_portone_secret_reaches_only_settlement_and_is_recorded(self):
        apps = ['auth', 'gateway', 'studio', 'review', 'catalog', 'store', 'order',
                'payment', 'license', 'download', 'settlement']
        inherited = {'SETTLEMENT_PORTONE_API_SECRET': 'settlement-portone-fixture',
                     'PORTONE_API_SECRET': 'payment-portone-fixture'}
        receivers = {app for app in apps if 'SETTLEMENT_PORTONE_API_SECRET' in service_environment(app, inherited)}
        self.assertEqual({'settlement'}, receivers)
        self.assertNotIn('PORTONE_API_SECRET', service_environment('settlement', inherited))
        self.assertIn('SETTLEMENT_PORTONE_API_SECRET', credential_names(inherited))

    def test_bootstrap_and_bff_credentials_are_separate(self):
        values = {'BFF_CLIENT_SECRET': 'bff-fixture', 'AUTH_ADMIN_PASSWORD': 'admin-fixture',
                  'AUTH_REVIEWER_PASSWORD': 'reviewer-fixture', 'PAYMENT_APPROVAL_PAYLOAD_KEY': 'payload-fixture'}
        self.assertEqual({'BFF_CLIENT_SECRET': 'bff-fixture'}, service_environment('gateway', values))
        self.assertEqual({'PAYMENT_APPROVAL_PAYLOAD_KEY': 'payload-fixture'}, service_environment('payment', values))
        self.assertEqual({key: value for key, value in values.items() if key != 'PAYMENT_APPROVAL_PAYLOAD_KEY'},
                         service_environment('auth', values))

    def test_storage_reader_credentials_remain_available_to_catalog(self):
        values = {'STORAGE_SECRET_KEY': 'upload-fixture', 'REVIEW_STORAGE_SECRET_KEY': 'review-fixture',
                  'DOWNLOAD_STORAGE_SECRET_KEY': 'reader-fixture', 'CATALOG_DOWNLOAD_STORAGE_SECRET_KEY': 'tag-fixture'}
        self.assertEqual({'DOWNLOAD_STORAGE_SECRET_KEY': 'reader-fixture',
                          'CATALOG_DOWNLOAD_STORAGE_SECRET_KEY': 'tag-fixture'}, service_environment('catalog', values))
        self.assertEqual({'REVIEW_STORAGE_SECRET_KEY': 'review-fixture'}, service_environment('review', values))
        self.assertEqual({'STORAGE_SECRET_KEY': 'upload-fixture'}, service_environment('studio', values))
        self.assertEqual({'DOWNLOAD_STORAGE_SECRET_KEY': 'reader-fixture'}, service_environment('download', values))

    def test_no_database_password_reaches_stateless_gateway_or_download(self):
        self.assertEqual({}, service_environment('gateway', {'DB_PASSWORD': 'db-fixture'}))
        self.assertEqual({}, service_environment('download', {'DB_PASSWORD': 'db-fixture'}))
        self.assertEqual({'DB_PASSWORD': 'db-fixture'}, service_environment('order', {'DB_PASSWORD': 'db-fixture'}))

    def test_pg_keys_are_injected_by_the_pg_runtime_only(self):
        values = {'MOCK_PG_PAYMENT_KEY': 'payment-fixture', 'MOCK_PG_SETTLEMENT_KEY': 'settlement-fixture',
                  'MOCK_PG_CONTROL_KEY': 'control-fixture', 'MOCK_PG_DB_PASSWORD': 'db-fixture'}
        for app in ['auth', 'catalog', 'order', 'payment', 'settlement']:
            self.assertEqual({}, service_environment(app, values))

    def test_preserves_non_secret_runtime_settings_without_mutating_input(self):
        values = {'PATH': '/usr/bin', 'JAVA_HOME': '/jdk', 'SERVER_PORT': '28080',
                  'CATALOG_ORDER_SERVICE_KEY': 'fixture'}
        selected = service_environment('gateway', values)
        self.assertEqual({'PATH': '/usr/bin', 'JAVA_HOME': '/jdk', 'SERVER_PORT': '28080'}, selected)
        self.assertIn('CATALOG_ORDER_SERVICE_KEY', values)

    def test_unknown_app_is_rejected(self):
        with self.assertRaises(ValueError):
            service_environment('typo-app', {})

    def test_audit_contains_names_only_and_recognizes_new_project_keys(self):
        values = {'CATALOG_FUTURE_SERVICE_KEY': 'never-log-this', 'CATALOG_ORDER_SERVICE_KEY': 'also-secret',
                  'SERVER_PORT': '28080'}
        self.assertEqual(['CATALOG_FUTURE_SERVICE_KEY', 'CATALOG_ORDER_SERVICE_KEY'], credential_names(values))
        self.assertEqual({'SERVER_PORT': '28080'}, service_environment('gateway', values))

    def test_rendered_base_and_local_compose_keep_credential_recipients(self):
        expected = {
            'CATALOG_STORE_SERVICE_KEY': {'catalog', 'store'},
            'CATALOG_ORDER_SERVICE_KEY': {'catalog', 'order'},
            'CATALOG_DOWNLOAD_SERVICE_KEY': {'catalog', 'download'},
            'CATALOG_SETTLEMENT_SERVICE_KEY': {'catalog', 'settlement'},
            'PAYMENT_ORDER_SERVICE_KEY': {'payment', 'order'},
            'PAYMENT_APPROVAL_PAYLOAD_KEY': {'payment'},
            'BFF_CLIENT_SECRET': {'auth', 'gateway'},
            'MOCK_PG_PAYMENT_KEY': {'payment', 'mock-pg'},
            'MOCK_PG_SETTLEMENT_KEY': {'settlement', 'mock-pg'},
            'MOCK_PG_CONTROL_KEY': {'mock-pg'},
            'DOWNLOAD_STORAGE_SECRET_KEY': {'catalog', 'download'},
        }
        environment = dict(os.environ)
        environment.update({name: 'g5-fixture-' + name.lower() for name in expected})
        environment.update(MOCK_PG_DB_PASSWORD='g5-db-fixture', MOCK_PG_DB_ROOT_PASSWORD='g5-root-fixture')
        root = Path(__file__).resolve().parents[2]
        result = subprocess.run(['docker', 'compose', '--env-file', '/dev/null',
                                 '-f', 'docker-compose.apps.yml', '-f', 'docker-compose.apps.local.yml',
                                 'config', '--format', 'json'], cwd=root, env=environment,
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        services = json.loads(result.stdout)['services']
        self.assertGreaterEqual(len(services), 12)
        for name, recipients in expected.items():
            actual = {app for app, definition in services.items() if name in definition.get('environment', {})}
            self.assertEqual(recipients, actual, name)
            for app in actual:
                self.assertEqual(environment[name], services[app]['environment'][name], name)


if __name__ == '__main__':
    unittest.main()
