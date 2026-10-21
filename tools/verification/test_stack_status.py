"""Regression checks for project-scoped readiness and failed bootstrap jobs."""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("stack_status", Path(__file__).with_name("stack-status.py"))
status = importlib.util.module_from_spec(spec)
spec.loader.exec_module(status)


class StackStatusTest(unittest.TestCase):
    def test_missing_service_is_not_success(self):
        self.assertIsNotNone(status.service_problem("mock-pg", []))

    def test_unhealthy_or_stopped_replica_fails(self):
        healthy = {"State": {"Running": True, "Status": "running", "Health": {"Status": "healthy"}}}
        unhealthy = {"State": {"Running": True, "Status": "running", "Health": {"Status": "unhealthy"}}}
        stopped = {"State": {"Running": False, "Status": "exited"}}
        self.assertIsNone(status.service_problem("auth", [healthy]))
        for other in (unhealthy, stopped):
            self.assertIsNotNone(status.service_problem("auth", [healthy, other]))

    def test_bootstrap_requires_successful_exit(self):
        for state in ({"Status": "running", "ExitCode": 0}, {"Status": "exited", "ExitCode": 1}):
            self.assertIsNotNone(status.service_problem("bootstrap", [{"State": state}], True))
        self.assertIsNone(status.service_problem("bootstrap", [{"State": {"Status": "exited", "ExitCode": 0}}], True))

    def test_inspection_only_uses_selected_project_and_services(self):
        command = ["docker", "compose", "-p", "isolated", "-f", "compose.json"]
        with patch.object(status, "capture", side_effect=["selected-id", '[{"State":{"Running":true,"Status":"running"}}]']) as capture:
            self.assertEqual([], status.check_stack(command, ["redis"], set()))
            self.assertEqual(command + ["ps", "--all", "--quiet", "redis"], capture.call_args_list[0].args[0])
            self.assertEqual(["docker", "inspect", "selected-id"], capture.call_args_list[1].args[0])


if __name__ == "__main__":
    unittest.main()
