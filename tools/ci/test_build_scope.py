"""결정 207: PR CI는 바뀐 범위만큼만 빌드한다. 판단 규칙의 계약 테스트."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_scope  # noqa: E402

APPS = {"order", "payment", "license"}


def scope(paths, event="pull_request"):
    return build_scope.decide(event, paths, APPS)


class BuildScopeTest(unittest.TestCase):
    def test_documentation_only_changes_skip_gradle(self):
        self.assertEqual(scope(["docs/decisions.md", ".kiro/specs/order/tasks.md", "README.md"]), [])

    def test_changes_inside_one_app_build_only_that_app(self):
        self.assertEqual(scope(["apps/order/src/main/java/A.java", "docs/evidence.d/300.md"]),
                         [":apps:order:build", ":common:test:test"])

    def test_app_changes_also_run_the_shared_tests_that_read_app_files(self):
        # common:test 의 배포 계약 테스트가 모든 앱의 application.yml 을 읽는다(PR #208 R1).
        self.assertIn(":common:test:test", scope(["apps/order/src/main/resources/application.yml"]))

    def test_changes_in_several_apps_build_each_of_them_in_a_stable_order(self):
        self.assertEqual(scope(["apps/payment/build.gradle", "apps/order/src/test/java/B.java"]),
                         [":apps:order:build", ":apps:payment:build", ":common:test:test"])

    def test_a_rename_is_judged_by_both_its_old_and_new_paths(self):
        # git diff --no-renames 가 옛 경로(삭제)와 새 경로(추가)를 둘 다 준다(PR #208 R2).
        self.assertEqual(scope(["apps/order/src/main/java/A.java", "docs/Archived.md"]),
                         [":apps:order:build", ":common:test:test"])
        self.assertEqual(scope(["apps/order/src/main/java/A.java", "apps/payment/src/main/java/A.java"]),
                         [":apps:order:build", ":apps:payment:build", ":common:test:test"])

    def test_shared_code_build_files_and_tooling_build_everything(self):
        # 공통 모듈·빌드 설정·시뮬레이터(Payment가 mock-pg jar에 기댐)·인프라(Catalog가 MinIO 정책에 기댐)는
        # 어느 앱이 영향을 받는지 경로로 알 수 없다.
        for path in ["common/messaging/src/main/java/X.java", "build.gradle", "settings.gradle",
                     "gradle/libs.versions.toml", "simulators/mock-pg/src/main/java/Y.java",
                     "infra/minio/policies/download-reader.json", ".github/workflows/ci.yml",
                     "tools/ci/build_scope.py", "e2e/build.gradle", "gradle.properties"]:
            self.assertEqual(scope([path, "apps/order/src/main/java/A.java"]), ["build"], path)

    def test_an_unknown_app_directory_builds_everything(self):
        self.assertEqual(scope(["apps/newapp/src/main/java/A.java"]), ["build"])

    def test_main_push_and_manual_runs_always_build_everything(self):
        for event in ["push", "workflow_dispatch", "schedule"]:
            self.assertEqual(scope(["docs/decisions.md"], event), ["build"], event)

    def test_no_changes_reported_builds_everything(self):
        # 변경 목록을 못 얻었으면 건너뛰지 않는다.
        self.assertEqual(scope([]), ["build"])

    def test_output_is_a_github_step_output_line(self):
        self.assertEqual(build_scope.output_line([":apps:order:build"]), "tasks=:apps:order:build")
        self.assertEqual(build_scope.output_line([]), "tasks=")


if __name__ == "__main__":
    unittest.main()
