"""Select project credentials for one verification process.

The allow-list lives in service_credentials.json next to this file. The e2e LocalStack
(ServiceCredentials.java) reads the same file, so the two launch paths cannot drift.
"""
import json
from pathlib import Path

_RULES = json.loads(Path(__file__).with_name('service_credentials.json').read_text(encoding='utf-8'))
_ALLOWED = {app: set(names) for app, names in _RULES['allowed'].items()}
# The PG prefixes stay although no app is allowed them: a leftover PG secret in the shell must not reach any app (결정 remove-pg-adapters).
_PREFIXES = tuple(_RULES['prefixes'])
_SUFFIXES = tuple(_RULES['suffixes'])


def _project_credential(name):
    return name == 'DB_PASSWORD' or (name.startswith(_PREFIXES) and name.endswith(_SUFFIXES))


def service_environment(app, inherited):
    if app not in _ALLOWED:
        raise ValueError('Unknown verification application')
    return {name: value for name, value in inherited.items()
            if not _project_credential(name) or name in _ALLOWED[app]}


def credential_names(environment):
    return sorted(name for name in environment if _project_credential(name))
