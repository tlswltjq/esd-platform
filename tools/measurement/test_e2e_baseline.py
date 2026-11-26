import argparse
import unittest

import e2e_baseline


class ArgumentsTest(unittest.TestCase):
    def test_rates_are_a_positive_comma_list_and_stage_names_are_path_safe(self):
        self.assertEqual(e2e_baseline.rates("2,5,10"), [2.0, 5.0, 10.0])
        with self.assertRaises(argparse.ArgumentTypeError):
            e2e_baseline.rates("2,0")
        self.assertEqual(e2e_baseline.stage_name(2.0), "r2")
        self.assertEqual(e2e_baseline.stage_name(12.5), "r12_5")

    def test_verify_command_passes_load_and_faults_and_skips_build_after_the_first_round(self):
        options = argparse.Namespace(seconds=180, warmup=30, buyers=20, fault=["kill:at=60,for=10,service=license"])
        command = e2e_baseline.verify_command(options, 5.0, skip_build=True)
        self.assertEqual(command[2:4], [str(e2e_baseline.ROOT / "tools/verification/verify-jib.py"), "--measure"])
        for expected in (["--load-rate", "5"], ["--load-duration", "180"], ["--load-warmup", "30"],
                         ["--fault", "kill:at=60,for=10,service=license"]):
            index = command.index(expected[0])
            self.assertEqual(command[index:index + 2], expected)
        self.assertEqual(command[-1], "--skip-build")
        self.assertNotIn("--skip-build", e2e_baseline.verify_command(options, 5.0, skip_build=False))

    def test_reads_the_evidence_folder_from_the_verify_log(self):
        self.assertEqual(e2e_baseline.evidence_path("build...\nEvidence: /repo/runs/messaging-measure-ab\nPASS"),
                         "/repo/runs/messaging-measure-ab")
        self.assertIsNone(e2e_baseline.evidence_path("no evidence line"))


class SummaryTest(unittest.TestCase):
    def test_stage_summary_keeps_values_without_judging(self):
        result = {"complete": True, "measurement": {
            "validity": {"valid": True, "reasons": [], "outcome": ["구매 근거 미완료 1건"]},
            "generator": {"achievedRate": 4.98}, "journey": {"paid_to_basis": {"p99_ms": 900}},
            "timeseries": {"rates": {"completed_per_s": 4.9}, "drain_seconds": 3.2, "unresolved": 0},
            "faults": {"faults": [{"kind": "kill", "problems": []}]}}}
        summary = e2e_baseline.stage_summary(result)
        self.assertEqual(summary["achieved_rate"], 4.98)
        self.assertEqual(summary["outcome"], ["구매 근거 미완료 1건"])
        self.assertEqual(summary["faults"], [{"kind": "kill", "problems": []}])
        self.assertEqual(summary["paid_to_basis"], {"p99_ms": 900})


def stage(complete=True, valid=True, unresolved=0, faults=None, reasons=None):
    return {"summary": {"complete": complete, "valid": valid, "unresolved": unresolved, "reasons": reasons or [],
                        "faults": faults or [], "error": None}}


ALL_FAULTS = [{"kind": spec.split(":", 1)[0], "problems": []} for spec in e2e_baseline.SMOKE_FAULTS]


class SmokeTest(unittest.TestCase):
    def test_passes_when_the_normal_round_is_valid_and_every_fault_happened(self):
        self.assertEqual(e2e_baseline.smoke_problems({"normal": stage(), "faults": stage(faults=ALL_FAULTS)}), [])

    def test_a_fault_problem_or_a_missing_fault_fails(self):
        broken = [dict(fault) for fault in ALL_FAULTS]
        broken[3] = {"kind": "dlt-limit", "problems": ["DLT 쓰기 실패를 일으키지 못했다"]}
        problems = e2e_baseline.smoke_problems({"normal": stage(), "faults": stage(faults=broken, valid=False)})
        self.assertIn("dlt-limit: DLT 쓰기 실패를 일으키지 못했다", problems)
        problems = e2e_baseline.smoke_problems({"normal": stage(), "faults": stage(faults=ALL_FAULTS[:2])})
        self.assertTrue(any("계획한 장애가 다 없다" in problem for problem in problems))

    def test_an_unresolved_normal_round_fails(self):
        problems = e2e_baseline.smoke_problems({"normal": stage(unresolved=2), "faults": stage(faults=ALL_FAULTS)})
        self.assertIn("정상 회차 미해소 2건", problems)


if __name__ == "__main__":
    unittest.main()
