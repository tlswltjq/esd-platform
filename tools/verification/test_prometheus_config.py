import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]


class PrometheusConfigTest(unittest.TestCase):
    def test_configured_applications_are_scraped_once_at_their_application_port(self):
        compose = yaml.safe_load((ROOT / "docker-compose.apps.yml").read_text())
        config = yaml.safe_load((ROOT / "infra/prometheus/prometheus.yml").read_text())
        expected = set()
        for service in compose["services"]:
            application = ROOT / "apps" / service / "src/main/resources/application.yml"
            if application.is_file():
                port = yaml.safe_load(application.read_text())["server"]["port"]
                expected.add(f"{service}:{port}")
        jobs = [job for job in config["scrape_configs"] if job["job_name"] == "stove-services"]
        self.assertEqual(len(jobs), 1)
        self.assertEqual(jobs[0]["metrics_path"], "/actuator/prometheus")
        targets = [target for group in jobs[0]["static_configs"] for target in group["targets"]]
        self.assertTrue(expected)
        self.assertCountEqual(targets, expected)

    def test_scraped_applications_tag_metrics_with_their_own_name(self):
        # Alerts and dashboards identify the failing service only through this label.
        compose = yaml.safe_load((ROOT / "docker-compose.apps.yml").read_text())
        checked = 0
        for service in compose["services"]:
            application = ROOT / "apps" / service / "src/main/resources/application.yml"
            if application.is_file():
                management = yaml.safe_load(application.read_text()).get("management") or {}
                with self.subTest(service=service):
                    self.assertEqual(configured_value(management, "metrics.tags.application"), service)
                checked += 1
        self.assertTrue(checked)


def configured_value(section, dotted_key):
    """Read a Spring property written either as nested maps or as one dotted key."""
    if dotted_key in section:
        return section[dotted_key]
    head, separator, rest = dotted_key.partition(".")
    child = section.get(head) if separator else None
    return configured_value(child, rest) if isinstance(child, dict) else None


if __name__ == "__main__":
    unittest.main()
