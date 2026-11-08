"""Prepare the shared MinIO/mc image without depending on a Dockerfile.

The selection rule mirrors minioImageSelection in the root build.gradle (docs/testing.md#minio-image).
Names in Docker Hub's `stove` namespace are refused instead of pulled (decision 135).
"""

import os
from pathlib import Path
import subprocess
from typing import Mapping, Tuple

from support.image_names import DEFAULT_REGISTRY, refuse_docker_hub_stove


TAG = "RELEASE.2025-04-22T22-12-26Z-local"
DEFAULT_IMAGE = DEFAULT_REGISTRY + "/minio:" + TAG


def _value(env: Mapping[str, str], name: str) -> str:
    return (env.get(name) or "").strip()


def select_minio_image(env: Mapping[str, str]) -> Tuple[str, str]:
    """Return the image name and whether it is built from source ("source") or pulled ("pull")."""
    test_image = refuse_docker_hub_stove("STOVE_TEST_MINIO_IMAGE", _value(env, "STOVE_TEST_MINIO_IMAGE"))
    supplied_image = refuse_docker_hub_stove("STOVE_MINIO_IMAGE", _value(env, "STOVE_MINIO_IMAGE"))
    registry = refuse_docker_hub_stove("STOVE_JIB_REGISTRY", _value(env, "STOVE_JIB_REGISTRY"), registry=True)
    default = (registry or DEFAULT_REGISTRY) + "/minio:" + TAG
    if test_image:
        return test_image, "source" if test_image in {DEFAULT_IMAGE, default} else "pull"
    if supplied_image:
        return supplied_image, "pull"
    return default, "source"


def ensure_minio_image(repo: Path) -> str:
    image, origin = select_minio_image(os.environ)
    present = subprocess.run(["docker", "image", "inspect", image],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if present.returncode:
        if origin == "pull":
            subprocess.run(["docker", "pull", image], check=True)
        else:
            subprocess.run(["./gradlew", ":infra:minio:jibDockerBuild", "-Pstove.minio.image=" + image,
                            "--no-daemon", "--console=plain"],
                           cwd=repo, check=True)
    return image
