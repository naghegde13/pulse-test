from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "backend_test_profile.py"
SPEC = importlib.util.spec_from_file_location("backend_test_profile", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
backend_test_profile = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = backend_test_profile
SPEC.loader.exec_module(backend_test_profile)


class BackendTestProfileTests(unittest.TestCase):
    def test_classify_live_runtime_it_into_dedicated_shard(self) -> None:
        info = backend_test_profile.classify_test_class(
            fqcn="com.pulse.e2e.runtime.CanonicalLoanMasterAirflowRuntimeIT",
            relative_path="com/pulse/e2e/runtime/CanonicalLoanMasterAirflowRuntimeIT.java",
            content='class CanonicalLoanMasterAirflowRuntimeIT { String c = "pulse-airflow-1"; }',
        )
        self.assertTrue(info.is_live_runtime)
        self.assertEqual("live-runtime", info.risk_area)
        self.assertEqual("live-runtime::CanonicalLoanMasterAirflowRuntimeIT", info.shard)

    def test_classify_non_live_e2e_runtime_support(self) -> None:
        info = backend_test_profile.classify_test_class(
            fqcn="com.pulse.e2e.runtime.LocalRuntimeArtifactRendererTest",
            relative_path="com/pulse/e2e/runtime/LocalRuntimeArtifactRendererTest.java",
            content="class LocalRuntimeArtifactRendererTest {}",
        )
        self.assertFalse(info.is_live_runtime)
        self.assertEqual("e2e-runtime", info.risk_area)
        self.assertEqual("e2e-runtime-support", info.shard)

    def test_resolve_fqcn_prefers_package_declaration_over_path(self) -> None:
        fqcn = backend_test_profile.resolve_fqcn(
            "com/pulse/deploy/DeployControllerTest.java",
            "package com.pulse.deploy.controller;\nclass DeployControllerTest {}",
        )
        self.assertEqual("com.pulse.deploy.controller.DeployControllerTest", fqcn)

    def test_projected_total_minutes_scales_from_completed_classes(self) -> None:
        projected = backend_test_profile.projected_total_minutes(
            completed_classes=2,
            elapsed_seconds=120.0,
            total_classes=6,
        )
        self.assertEqual(6.0, projected)

    def test_discover_test_classes_filters_non_tests_and_groups(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "com/pulse/auth").mkdir(parents=True)
            (root / "com/pulse/e2e/runtime").mkdir(parents=True)
            (root / "com/pulse/auth/AuthControllerTest.java").write_text("class AuthControllerTest {}", encoding="utf-8")
            (root / "com/pulse/auth/Helper.java").write_text("class Helper {}", encoding="utf-8")
            (root / "com/pulse/e2e/runtime/LocalRuntimeBridgeIT.java").write_text(
                "@SpringBootTest class LocalRuntimeBridgeIT {}",
                encoding="utf-8",
            )

            classes = backend_test_profile.discover_test_classes(root)
            self.assertEqual(2, len(classes))
            shards = backend_test_profile.group_into_shards(classes)
            shard_names = [shard_name for shard_name, _, _ in shards]
            self.assertIn("auth", shard_names)
            self.assertIn("e2e-runtime-support", shard_names)

    def test_format_sidecar_progress_summarizes_known_fields(self) -> None:
        summary = backend_test_profile.format_sidecar_progress(
            "timestamp=2026-04-26T17:02:10Z completed=9 total=29 percent=31.0% elapsed=3.2s avg_per_scenario=0.4s eta=7.2s last_scenario=transform-generic-router-investor-state-static-deployability"
        )
        self.assertIn("completed=9/29", summary)
        self.assertIn("percent=31.0%", summary)
        self.assertIn("eta=7.2s", summary)

    def test_read_sidecar_progress_returns_latest_line(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            backend_dir = Path(tmp)
            progress_path = backend_dir / "build/e2e/scenario-catalog-progress.log"
            progress_path.parent.mkdir(parents=True)
            progress_path.write_text(
                "timestamp=a completed=1 total=29 percent=3.4% eta=13.5s last_scenario=one\n"
                "timestamp=b completed=2 total=29 percent=6.9% eta=13.2s last_scenario=two\n",
                encoding="utf-8",
            )
            mtime, line = backend_test_profile.read_sidecar_progress(
                backend_dir,
                "com.pulse.e2e.builder.LoanMasterScenarioCatalogExecutionIT",
            )
            self.assertIsNotNone(mtime)
            self.assertIn("completed=2", line)

    def test_recommendation_prefers_sharded_retries_when_blocked(self) -> None:
        shard_reports = [
            backend_test_profile.ShardRunReport(
                name="codegen",
                risk_area="codegen",
                class_count=2,
                classes=["A", "B"],
                status="over_budget",
                elapsed_seconds=90.0,
                completed_classes=1,
                blocker="projected runtime 52.0m exceeds budget 45.0m",
            ),
            backend_test_profile.ShardRunReport(
                name="live-runtime::CanonicalLoanMasterAirflowRuntimeIT",
                risk_area="live-runtime",
                class_count=1,
                classes=["C"],
                status="skipped_live_runtime",
                elapsed_seconds=0.0,
                completed_classes=0,
                blocker="skipped live-runtime expansion by policy",
            ),
        ]
        plan = backend_test_profile.make_recommended_plan(shard_reports, full_gradlew_test_feasible=False)
        self.assertTrue(any("Do not rerun plain ./gradlew test yet" in step for step in plan))
        self.assertTrue(any("excluding live-runtime expansion shards" in step for step in plan))
        self.assertTrue(any("Split over-budget shards further" in step for step in plan))


if __name__ == "__main__":
    unittest.main()
