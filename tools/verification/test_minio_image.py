from pathlib import Path
import subprocess
import unittest
from unittest import mock

from support import minio_image
from support.minio_image import ensure_minio_image, select_minio_image

TAG = "RELEASE.2025-04-22T22-12-26Z-local"
FIXED = "localhost/stove/minio:" + TAG
LEGACY = "stove/minio:" + TAG


class SelectMinioImageTest(unittest.TestCase):
    def test_defaults_to_source_build_of_fixed_name(self):
        self.assertEqual((FIXED, "source"), select_minio_image({}))

    def test_registry_changes_the_source_build_name(self):
        self.assertEqual(("example.invalid/team/minio:" + TAG, "source"),
                         select_minio_image({"STOVE_JIB_REGISTRY": "example.invalid/team"}))

    def test_fixed_name_is_built_even_with_another_registry(self):
        env = {"STOVE_JIB_REGISTRY": "example.invalid/team", "STOVE_TEST_MINIO_IMAGE": FIXED}
        self.assertEqual((FIXED, "source"), select_minio_image(env))

    def test_registry_name_is_built_when_selected_for_tests(self):
        image = "example.invalid/team/minio:" + TAG
        env = {"STOVE_JIB_REGISTRY": "example.invalid/team", "STOVE_TEST_MINIO_IMAGE": image}
        self.assertEqual((image, "source"), select_minio_image(env))

    def test_other_test_image_is_pulled(self):
        self.assertEqual(("vendor/minio:test", "pull"),
                         select_minio_image({"STOVE_TEST_MINIO_IMAGE": "vendor/minio:test"}))

    def test_supplied_image_without_test_image_is_pulled(self):
        self.assertEqual((FIXED, "pull"), select_minio_image({"STOVE_MINIO_IMAGE": FIXED}))

    def test_test_image_wins_over_supplied_image(self):
        env = {"STOVE_TEST_MINIO_IMAGE": FIXED, "STOVE_MINIO_IMAGE": "vendor/minio:test"}
        self.assertEqual((FIXED, "source"), select_minio_image(env))

    def test_blank_values_are_ignored(self):
        env = {"STOVE_TEST_MINIO_IMAGE": "  ", "STOVE_MINIO_IMAGE": "", "STOVE_JIB_REGISTRY": " "}
        self.assertEqual((FIXED, "source"), select_minio_image(env))

    def test_values_are_trimmed(self):
        self.assertEqual((FIXED, "source"), select_minio_image({"STOVE_TEST_MINIO_IMAGE": " " + FIXED + " "}))

    def test_docker_hub_stove_names_are_refused_instead_of_pulled(self):
        for name, value in [("STOVE_TEST_MINIO_IMAGE", LEGACY), ("STOVE_MINIO_IMAGE", "docker.io/" + LEGACY),
                            ("STOVE_JIB_REGISTRY", "stove")]:
            with self.subTest(name=name):
                with self.assertRaises(ValueError) as raised:
                    select_minio_image({name: value})
                self.assertIn(name, str(raised.exception))


class EnsureMinioImageTest(unittest.TestCase):
    def setUp(self):
        self.repo = Path("/repo")
        self.calls = []

    def run_with(self, inspect_code):
        def run(command, **kwargs):
            self.calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, inspect_code if command[:3] == ["docker", "image", "inspect"] else 0)
        return run

    def ensure(self, env, inspect_code):
        with mock.patch.dict(minio_image.os.environ, env, clear=True), \
                mock.patch.object(minio_image.subprocess, "run", self.run_with(inspect_code)):
            return ensure_minio_image(self.repo)

    def test_present_image_is_reused(self):
        self.assertEqual(FIXED, self.ensure({"STOVE_TEST_MINIO_IMAGE": FIXED}, 0))
        self.assertEqual([["docker", "image", "inspect", FIXED]], [command for command, _ in self.calls])

    def test_missing_source_image_is_built_under_that_name(self):
        self.ensure({"STOVE_TEST_MINIO_IMAGE": FIXED, "STOVE_MINIO_IMAGE": "vendor/minio:test"}, 1)
        command, kwargs = self.calls[-1]
        self.assertEqual(["./gradlew", ":infra:minio:jibDockerBuild", "-Pstove.minio.image=" + FIXED],
                         command[:3])
        self.assertEqual(self.repo, kwargs["cwd"])
        self.assertNotIn("pull", [part for command, _ in self.calls for part in command])

    def test_missing_supplied_image_is_pulled(self):
        self.ensure({"STOVE_MINIO_IMAGE": "vendor/minio:test"}, 1)
        self.assertEqual(["docker", "pull", "vendor/minio:test"], self.calls[-1][0])

    def test_legacy_name_runs_no_docker_command(self):
        with self.assertRaises(ValueError):
            self.ensure({"STOVE_TEST_MINIO_IMAGE": LEGACY}, 1)
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
