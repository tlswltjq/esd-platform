"""Select application artifacts without accidentally launching test fixtures."""


def application_jar(repo, app):
    candidates = [path for path in (repo / 'apps' / app / 'build/libs').glob('*.jar')
                  if not path.name.endswith(('-plain.jar', '-test-fixtures.jar'))]
    if len(candidates) != 1:
        raise RuntimeError('Expected exactly one runnable artifact for ' + app)
    return candidates[0]
