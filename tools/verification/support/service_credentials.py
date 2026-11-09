"""Select project credentials for one verification process."""


_DB = {'DB_PASSWORD'}
_BFF = {'BFF_CLIENT_SECRET'}
_CATALOG_KEYS = {'CATALOG_STORE_SERVICE_KEY', 'CATALOG_ORDER_SERVICE_KEY',
                 'CATALOG_DOWNLOAD_SERVICE_KEY', 'CATALOG_SETTLEMENT_SERVICE_KEY'}
_READER = {'DOWNLOAD_STORAGE_ACCESS_KEY', 'DOWNLOAD_STORAGE_SECRET_KEY'}
_ALLOWED = {
    'auth': _DB | _BFF | {'AUTH_ADMIN_PASSWORD', 'AUTH_REVIEWER_PASSWORD'},
    'gateway': _BFF,
    'studio': _DB | {'STORAGE_ACCESS_KEY', 'STORAGE_SECRET_KEY'},
    'review': _DB | {'REVIEW_STORAGE_ACCESS_KEY', 'REVIEW_STORAGE_SECRET_KEY'},
    # Catalog verifies tagged objects through a separate reader client.
    'catalog': _DB | _CATALOG_KEYS | _READER | {'CATALOG_DOWNLOAD_STORAGE_ACCESS_KEY',
                                             'CATALOG_DOWNLOAD_STORAGE_SECRET_KEY'},
    'store': _DB | {'CATALOG_STORE_SERVICE_KEY'},
    'order': _DB | {'CATALOG_ORDER_SERVICE_KEY', 'PAYMENT_ORDER_SERVICE_KEY'},
    'payment': _DB | {'PAYMENT_ORDER_SERVICE_KEY', 'PAYMENT_APPROVAL_PAYLOAD_KEY',
                      'PORTONE_API_SECRET', 'PORTONE_WEBHOOK_SECRET'},
    'license': _DB,
    'download': _READER | {'CATALOG_DOWNLOAD_SERVICE_KEY'},
    # Settlement's PortOne secret is named apart from Payment's (docker-compose.settlement-portone.yml).
    'settlement': _DB | {'CATALOG_SETTLEMENT_SERVICE_KEY', 'SETTLEMENT_PORTONE_API_SECRET'},
}
_PREFIXES = ('CATALOG_', 'PAYMENT_', 'MOCK_PG_', 'PORTONE_', 'SETTLEMENT_PORTONE_', 'AUTH_', 'BFF_',
             'STORAGE_', 'REVIEW_STORAGE_', 'DOWNLOAD_STORAGE_')


def _project_credential(name):
    return name == 'DB_PASSWORD' or (name.startswith(_PREFIXES)
                                    and name.endswith(('_KEY', '_SECRET', '_PASSWORD')))


def service_environment(app, inherited):
    if app not in _ALLOWED:
        raise ValueError('Unknown verification application')
    return {name: value for name, value in inherited.items()
            if not _project_credential(name) or name in _ALLOWED[app]}


def credential_names(environment):
    return sorted(name for name in environment if _project_credential(name))
