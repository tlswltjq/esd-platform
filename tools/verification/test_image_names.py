import unittest

from support.image_names import DEFAULT_REGISTRY, docker_hub_stove, refuse_docker_hub_stove


class DockerHubStoveTest(unittest.TestCase):
    def test_default_registry_is_not_docker_hub(self):
        self.assertEqual("localhost/stove", DEFAULT_REGISTRY)
        self.assertFalse(docker_hub_stove(DEFAULT_REGISTRY + "/minio:tag"))

    def test_names_without_registry_host_resolve_to_docker_hub(self):
        for image in ["stove/minio:tag", "stove/gateway", "stove/minio@sha256:" + "0" * 64]:
            with self.subTest(image=image):
                self.assertTrue(docker_hub_stove(image))

    def test_explicit_docker_hub_hosts_are_docker_hub(self):
        for host in ["docker.io", "index.docker.io", "registry-1.docker.io"]:
            with self.subTest(host=host):
                self.assertTrue(docker_hub_stove(host + "/stove/minio:tag"))

    def test_other_registries_and_namespaces_are_allowed(self):
        for image in ["localhost/stove/minio:tag", "ghcr.io/stove/minio:tag", "127.0.0.1:5000/stove/minio",
                      "registry.example:443/stove/minio", "stove-team/minio:tag", "stove", "minio/minio",
                      "docker.io/library/stove"]:
            with self.subTest(image=image):
                self.assertFalse(docker_hub_stove(image))

    def test_refusal_names_the_setting_and_the_replacement(self):
        with self.assertRaises(ValueError) as raised:
            refuse_docker_hub_stove("STOVE_TEST_MINIO_IMAGE", "stove/minio:tag")
        message = str(raised.exception)
        self.assertIn("STOVE_TEST_MINIO_IMAGE", message)
        self.assertIn("localhost/stove/minio:tag", message)

    def test_refusal_passes_allowed_names_through(self):
        self.assertEqual("localhost/stove/minio:tag",
                         refuse_docker_hub_stove("STOVE_TEST_MINIO_IMAGE", "localhost/stove/minio:tag"))


if __name__ == "__main__":
    unittest.main()
