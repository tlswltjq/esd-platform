"""Image names this repository builds itself (decision 118).

Names without a registry host resolve to Docker Hub, and Docker Hub's `stove` namespace belongs to an
unrelated account. Self-built images therefore default to `localhost/stove`, which Docker never pulls
from Docker Hub. The same rule lives in the root build.gradle and InfraContainers.
"""

DEFAULT_REGISTRY = "localhost/stove"
DOCKER_HUB_HOSTS = {"docker.io", "index.docker.io", "registry-1.docker.io"}


def _repository(image: str) -> list:
    name = image.strip().split("@", 1)[0]
    head, _, last = name.rpartition("/")
    last = last.split(":", 1)[0]
    return (head + "/" + last if head else last).split("/")


def docker_hub_stove(image: str) -> bool:
    """Whether Docker would pull this name from Docker Hub's `stove` namespace."""
    parts = _repository(image)
    first = parts[0]
    if len(parts) > 1 and ("." in first or ":" in first or first == "localhost"):
        if first.lower() not in DOCKER_HUB_HOSTS:
            return False
        parts = parts[1:]
    return len(parts) > 1 and parts[0] == "stove"


def refuse_docker_hub_stove(setting: str, value: str, registry: bool = False) -> str:
    """Return the value, or refuse a name that points at Docker Hub's `stove` namespace."""
    if not docker_hub_stove(value + "/image" if registry else value):
        return value
    parts = value.strip().split("/")
    if parts[0].lower() in DOCKER_HUB_HOSTS:
        parts = parts[1:]
    replacement = "/".join([DEFAULT_REGISTRY] + parts[1:])
    raise ValueError(setting + "=" + value + " 는 Docker Hub의 다른 사람 계정(stove)을 가리킨다(결정 118). "
                     + replacement + " 로 바꾼다.")
