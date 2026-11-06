#!/usr/bin/env python3
"""Check or start app Compose using externally injected credentials.

No credential file is read or written. Values stay out of command arguments.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


REQUIRED = (
    'DB_PASSWORD', 'STORAGE_ACCESS_KEY', 'STORAGE_SECRET_KEY',
    'BFF_CLIENT_SECRET', 'AUTH_ADMIN_PASSWORD', 'AUTH_REVIEWER_PASSWORD',
    'CATALOG_STORE_SERVICE_KEY', 'CATALOG_ORDER_SERVICE_KEY',
    'CATALOG_DOWNLOAD_SERVICE_KEY', 'CATALOG_SETTLEMENT_SERVICE_KEY',
    'PAYMENT_ORDER_SERVICE_KEY', 'PAYMENT_APPROVAL_PAYLOAD_KEY',
    'MOCK_PG_PAYMENT_KEY', 'MOCK_PG_SETTLEMENT_KEY', 'MOCK_PG_CONTROL_KEY',
    'MOCK_PG_DB_PASSWORD', 'MOCK_PG_DB_ROOT_PASSWORD',
    'REVIEW_STORAGE_ACCESS_KEY', 'REVIEW_STORAGE_SECRET_KEY',
    'DOWNLOAD_STORAGE_ACCESS_KEY', 'DOWNLOAD_STORAGE_SECRET_KEY',
)


def deployment_environment(environment):
    if any(name.startswith('COMPOSE_') for name in environment):
        raise ValueError('Compose control overrides are not allowed')
    if any(not environment.get(name, '').strip() for name in REQUIRED):
        raise ValueError('Required deployment credentials are missing')
    if environment.get('CATALOG_DOWNLOAD_STORAGE_ENABLED', '').lower() == 'true':
        if any(not environment.get(name, '').strip() for name in (
                'CATALOG_DOWNLOAD_STORAGE_ACCESS_KEY', 'CATALOG_DOWNLOAD_STORAGE_SECRET_KEY')):
            raise ValueError('Catalog storage credentials are missing')
    return dict(environment)


def compose_command(project, root):
    return ['docker', 'compose', '--project-name', project,
            '--env-file', '/dev/null', '-f', str(root / 'docker-compose.apps.yml'),
            '-f', str(root / 'docker-compose.injected.yml')]


def quiet_run(command, root, environment):
    return subprocess.run(command, cwd=root, env=environment,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                          check=False).returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-name', required=True)
    parser.add_argument('--apply', action='store_true', help='Start apps after validation')
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', arguments.project_name):
            raise ValueError('Invalid project name')
        environment = deployment_environment(os.environ)
        command = compose_command(arguments.project_name, root)
        if quiet_run(command + ['config', '--quiet'], root, environment):
            raise ValueError('Compose validation failed')
        if arguments.apply:
            if quiet_run(['bash', str(root / 'scripts/start-apps.sh')] + command[2:],
                         root, environment):
                raise ValueError('App startup failed')
    except (OSError, ValueError):
        print('Deployment failed; check injected keys and app health. Values suppressed. '
              'With --apply, some services may have started.',
              file=sys.stderr)
        return 1
    print('App startup completed.' if arguments.apply else
          'Injected credentials and Compose syntax checked. No services started.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
