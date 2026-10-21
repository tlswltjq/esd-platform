"""Prepare the shared MinIO/mc image without depending on a Dockerfile."""

import os
from pathlib import Path
import subprocess


DEFAULT_IMAGE = "stove/minio:RELEASE.2025-04-22T22-12-26Z-local"


def ensure_minio_image(repo: Path) -> str:
    override = os.environ.get("STOVE_TEST_MINIO_IMAGE") or os.environ.get("STOVE_MINIO_IMAGE")
    default = (os.environ.get("STOVE_JIB_REGISTRY") or "stove") + "/minio:RELEASE.2025-04-22T22-12-26Z-local"
    image = override or default
    present = subprocess.run(["docker", "image", "inspect", image],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if present.returncode:
        if override and (image not in {DEFAULT_IMAGE, default} or os.environ.get("STOVE_MINIO_IMAGE")):
            subprocess.run(["docker", "pull", image], check=True)
        else:
            subprocess.run(["./gradlew", ":infra:minio:jibDockerBuild", "-Pstove.minio.image=" + image,
                            "--no-daemon", "--console=plain"],
                           cwd=repo, check=True)
    return image
