"""Prepare the shared MinIO/mc image without depending on a Dockerfile.

The selection rule mirrors minioImageSelection in the root build.gradle (docs/testing.md#minio-image).
"""

import os
from pathlib import Path
import subprocess
from typing import Mapping, Tuple


TAG = "RELEASE.2025-04-22T22-12-26Z-local"
DEFAULT_IMAGE = "stove/minio:" + TAG


def _value(env: Mapping[str, str], name: str) -> str:
    return (env.get(name) or "").strip()


def select_minio_image(env: Mapping[str, str]) -> Tuple[str, str]:
    """Return the image name and whether it is built from source ("source") or pulled ("pull")."""
    test_image = _value(env, "STOVE_TEST_MINIO_IMAGE")
    supplied_image = _value(env, "STOVE_MINIO_IMAGE")
    default = (_value(env, "STOVE_JIB_REGISTRY") or "stove") + "/minio:" + TAG
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
