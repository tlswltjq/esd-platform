import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from support.image_names import DEFAULT_REGISTRY, docker_hub_stove, refuse_docker_hub_stove

ROOT = Path(__file__).resolve().parents[2]
CHECK_SCRIPT = ROOT / "scripts" / "check-image-names.sh"
DIGEST = "@sha256:" + "0" * 64

# (image, resolves to Docker Hub's stove namespace)
CASES = [
    ("stove/minio:tag", True), ("stove/gateway", True), ("stove/minio" + DIGEST, True),
    ("stove/minio:tag" + DIGEST, True), ("docker.io/stove/minio:tag", True), ("index.docker.io/stove/minio", True),
    ("registry-1.docker.io/stove/minio", True), ("Docker.IO/stove/minio:tag", True),
    ("localhost/stove/minio:tag", False), ("localhost:5000/stove/minio:tag", False),
    ("ghcr.io/stove/minio:tag", False), ("127.0.0.1:5000/stove/minio", False),
    ("registry.example:443/stove/minio", False), ("stove-team/minio:tag", False), ("stove", False),
    ("minio/minio", False), ("docker.io/library/stove", False), ("mysql:8.0", False),
]


def load_backup():
    spec = importlib.util.spec_from_file_location("backup_tool", ROOT / "tools" / "operations" / "backup.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def shell_docker_hub_stove(image):
    result = subprocess.run(["bash", "-c", 'source "$1"; docker_hub_stove "$2"', "_", str(CHECK_SCRIPT), image])
    return result.returncode == 0


class DockerHubStoveTest(unittest.TestCase):
    def test_default_registry_is_not_docker_hub(self):
        self.assertEqual("localhost/stove", DEFAULT_REGISTRY)
        self.assertFalse(docker_hub_stove(DEFAULT_REGISTRY + "/minio:tag"))

    def test_cases(self):
        for image, expected in CASES:
            with self.subTest(image=image):
                self.assertEqual(expected, docker_hub_stove(image))

    def test_refusal_names_the_setting_and_the_replacement(self):
        with self.assertRaises(ValueError) as raised:
            refuse_docker_hub_stove("STOVE_TEST_MINIO_IMAGE", "stove/minio:tag")
        message = str(raised.exception)
        self.assertIn("STOVE_TEST_MINIO_IMAGE", message)
        self.assertIn("localhost/stove/minio:tag", message)

    def test_refusal_passes_allowed_names_through(self):
        self.assertEqual("localhost/stove/minio:tag",
                         refuse_docker_hub_stove("STOVE_TEST_MINIO_IMAGE", "localhost/stove/minio:tag"))


class SameRuleEverywhereTest(unittest.TestCase):
    """The compose check script and the operations backup tool use their own copies of the rule."""

    def test_shell_check_matches(self):
        for image, expected in CASES:
            with self.subTest(image=image):
                self.assertEqual(expected, shell_docker_hub_stove(image))

    def test_backup_tool_matches(self):
        backup = load_backup()
        for image, expected in CASES:
            with self.subTest(image=image):
                self.assertEqual(expected, backup.docker_hub_stove(image))


class ComposeCheckScriptTest(unittest.TestCase):
    """scripts/check-image-names.sh checks the images Compose resolved, .env included, before starting."""

    def run_check(self, images):
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory) / "docker"
            fake.write_text('#!/usr/bin/env bash\n'
                            'if [ "$1" = compose ] && [ "${@: -2:1}" = config ] && [ "${@: -1}" = --images ]; then\n'
                            '  printf "%s\\n" ' + " ".join('"' + image + '"' for image in images) + '\n'
                            '  exit 0\nfi\nexit 9\n')
            fake.chmod(0o755)
            environment = dict(os.environ, PATH=directory + os.pathsep + os.environ["PATH"])
            return subprocess.run(["bash", str(CHECK_SCRIPT), "docker", "compose", "-f", "docker-compose.apps.yml"],
                                  env=environment, capture_output=True, text=True)

    def test_local_images_pass(self):
        result = self.run_check(["localhost/stove/gateway:latest", "mysql:8.0", "ghcr.io/team/auth:1"])
        self.assertEqual(0, result.returncode, result.stderr)

    def test_docker_hub_stove_images_stop_the_start(self):
        result = self.run_check(["localhost/stove/auth:latest", "stove/gateway:latest", "docker.io/stove/minio:tag"])
        self.assertEqual(1, result.returncode)
        self.assertIn("stove/gateway:latest", result.stderr)
        self.assertIn("docker.io/stove/minio:tag", result.stderr)
        self.assertNotIn("localhost/stove/auth:latest", result.stderr)
        self.assertIn("결정 118", result.stderr)


if __name__ == "__main__":
    unittest.main()
