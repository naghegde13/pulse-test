#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import select
import subprocess
import sys
import tempfile
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable

EVENT_PREFIX = "__OMX_TEST_EVENT__ "
# Classes treated as live-runtime by name. After the wave0 CI-lane split these
# also carry @Tag("runtime") and are excluded from fastPrTest /
# backendIntegrationTest at the Gradle layer; this list keeps the Python
# profiler in sync so dry-run shard plans label them correctly.
LIVE_RUNTIME_CLASS_MARKERS = (
    "CanonicalLoanMasterAirflowRuntimeIT",
    "JsonBlueprintLiveRuntimeProofIT",
    "AggregateBlueprintLiveRuntimeProofIT",
)
LIVE_RUNTIME_CONTENT_MARKERS = (
    "PULSE_E2E_RETAIN_RUNTIME_REPO",
    "triggerDag(",
    "awaitDagExists(",
    "pulse-minio-1",
    "@Tag(\"runtime\")",
)
INTEGRATION_MARKERS = (
    "@SpringBootTest",
    "@AutoConfigureMockMvc",
    "@Transactional",
)
DEFAULT_REPORT_DIR = Path("backend/build/test-profile")
DEFAULT_MAX_PROJECTED_MINUTES = 45.0
DEFAULT_NO_PROGRESS_MINUTES = 12.0
DEFAULT_HANG_CLASS_MINUTES = 15.0
DEFAULT_MIN_COMPLETED_FOR_PROJECTION = 2

SIDE_CAR_PROGRESS_FILES = {
    "com.pulse.e2e.builder.LoanMasterScenarioCatalogExecutionIT": Path("build/e2e/scenario-catalog-progress.log"),
}


@dataclass(frozen=True)
class TestClassInfo:
    fqcn: str
    relative_path: str
    shard: str
    risk_area: str
    package_bucket: str
    is_integration: bool
    is_live_runtime: bool


@dataclass
class ClassRun:
    class_name: str
    duration_ms: int
    status: str
    test_count: int
    failed_count: int
    skipped_count: int


@dataclass
class ShardRunReport:
    name: str
    risk_area: str
    class_count: int
    classes: list[str]
    status: str
    elapsed_seconds: float
    completed_classes: int
    projected_total_minutes: float | None = None
    eta_minutes: float | None = None
    slow_classes: list[dict[str, object]] = field(default_factory=list)
    active_class_at_stop: str | None = None
    blocker: str | None = None
    failure_summary: str | None = None
    log_path: str | None = None


@dataclass
class ProfileSummary:
    generated_at: str
    backend_dir: str
    shard_count: int
    executed_shard_count: int
    skipped_live_runtime_shard_count: int
    full_gradlew_test_feasible: bool
    blockers: list[str]
    slow_classes: list[dict[str, object]]
    recommended_verification_plan: list[str]
    shard_reports: list[ShardRunReport]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Profile backend Gradle tests in bounded shards, emit progress/ETA, "
            "skip live-runtime expansion by default, and stop shards projected over budget."
        )
    )
    parser.add_argument("--backend-dir", default="backend", help="Backend project directory (default: backend)")
    parser.add_argument(
        "--report-dir",
        default=str(DEFAULT_REPORT_DIR),
        help=f"Directory for JSON/markdown/log reports (default: {DEFAULT_REPORT_DIR})",
    )
    parser.add_argument("--gradle-task", default="test", help="Gradle test task to run (default: test)")
    parser.add_argument(
        "--gradle-bin",
        default="gradlew",
        help="Gradle launcher to use (default: gradlew in backend dir; can be absolute path)",
    )
    parser.add_argument(
        "--max-projected-minutes",
        type=float,
        default=DEFAULT_MAX_PROJECTED_MINUTES,
        help=f"Abort a shard when projected total runtime exceeds this budget (default: {DEFAULT_MAX_PROJECTED_MINUTES})",
    )
    parser.add_argument(
        "--no-progress-minutes",
        type=float,
        default=DEFAULT_NO_PROGRESS_MINUTES,
        help=f"Abort if a shard produces no class completions for this long (default: {DEFAULT_NO_PROGRESS_MINUTES})",
    )
    parser.add_argument(
        "--hang-class-minutes",
        type=float,
        default=DEFAULT_HANG_CLASS_MINUTES,
        help=f"Abort if one active class runs this long without finishing (default: {DEFAULT_HANG_CLASS_MINUTES})",
    )
    parser.add_argument(
        "--min-completed-classes-for-projection",
        type=int,
        default=DEFAULT_MIN_COMPLETED_FOR_PROJECTION,
        help=(
            "Need at least this many completed classes before ETA/projection can kill a shard "
            f"(default: {DEFAULT_MIN_COMPLETED_FOR_PROJECTION})"
        ),
    )
    parser.add_argument("--include-live-runtime", action="store_true", help="Execute live-runtime shards too")
    parser.add_argument("--dry-run", action="store_true", help="Only print shard plan; do not invoke Gradle")
    parser.add_argument(
        "--only-shard",
        action="append",
        default=[],
        help="Limit execution to specific shard name(s). Repeat as needed.",
    )
    parser.add_argument(
        "--limit-shards",
        type=int,
        default=0,
        help="Execute at most N shards after filtering (0 = no limit)",
    )
    parser.add_argument(
        "--stop-on-failure",
        action="store_true",
        help="Stop after the first failed/over-budget shard instead of continuing",
    )
    return parser.parse_args()


PACKAGE_DECLARATION_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)


def discover_test_classes(test_root: Path) -> list[TestClassInfo]:
    classes: list[TestClassInfo] = []
    for path in sorted(test_root.rglob("*.java")):
        if not (path.name.endswith("Test.java") or path.name.endswith("IT.java")):
            continue
        relative = path.relative_to(test_root).as_posix()
        content = path.read_text(encoding="utf-8")
        fqcn = resolve_fqcn(relative, content)
        classes.append(classify_test_class(fqcn, relative, content))
    return classes


def resolve_fqcn(relative_path: str, content: str) -> str:
    match = PACKAGE_DECLARATION_RE.search(content)
    class_name = Path(relative_path).stem
    if match:
        return f"{match.group(1)}.{class_name}"
    return relative_path.removesuffix(".java").replace("/", ".")


def classify_test_class(fqcn: str, relative_path: str, content: str) -> TestClassInfo:
    parts = fqcn.split(".")
    package_bucket = parts[2] if len(parts) > 2 else parts[-1].lower()
    is_live_runtime = is_live_runtime_test(relative_path, content)
    is_integration = is_integration_test(relative_path, content)
    risk_area, shard = shard_for_test(fqcn, relative_path, package_bucket, is_integration, is_live_runtime)
    return TestClassInfo(
        fqcn=fqcn,
        relative_path=relative_path,
        shard=shard,
        risk_area=risk_area,
        package_bucket=package_bucket,
        is_integration=is_integration,
        is_live_runtime=is_live_runtime,
    )


def is_live_runtime_test(relative_path: str, content: str) -> bool:
    if any(marker in relative_path for marker in LIVE_RUNTIME_CLASS_MARKERS):
        return True
    if "docker" in content and any(marker in content for marker in LIVE_RUNTIME_CONTENT_MARKERS):
        return True
    return False


def is_integration_test(relative_path: str, content: str) -> bool:
    return relative_path.endswith("IT.java") or any(marker in content for marker in INTEGRATION_MARKERS)


def shard_for_test(
    fqcn: str,
    relative_path: str,
    package_bucket: str,
    is_integration: bool,
    is_live_runtime: bool,
) -> tuple[str, str]:
    if is_live_runtime:
        class_name = fqcn.split(".")[-1]
        return "live-runtime", f"live-runtime::{class_name}"

    if relative_path.startswith("com/pulse/e2e/"):
        subgroup = relative_path.split("/", 4)[3]
        risk_area = f"e2e-{subgroup}"
        shard = risk_area
        if subgroup == "validation":
            shard = f"{risk_area}-integration"
        elif subgroup == "api":
            shard = f"{risk_area}-scenarios"
        elif subgroup == "runtime":
            shard = "e2e-runtime-support"
        return risk_area, shard

    if package_bucket in {"deploy", "pipeline", "sor", "secret", "git", "chat", "codegen"} and is_integration:
        return f"{package_bucket}-integration", f"{package_bucket}-integration"

    return package_bucket, package_bucket


def group_into_shards(classes: Iterable[TestClassInfo]) -> list[tuple[str, str, list[TestClassInfo]]]:
    grouped: dict[tuple[str, str], list[TestClassInfo]] = {}
    for test_class in classes:
        key = (test_class.shard, test_class.risk_area)
        grouped.setdefault(key, []).append(test_class)

    risk_order = {
        "common": 10,
        "config": 10,
        "expression": 10,
        "storage": 10,
        "auth": 20,
        "blueprint": 20,
        "secret": 20,
        "git": 20,
        "pipeline": 20,
        "deploy": 20,
        "sor": 20,
        "codegen": 30,
        "chat": 30,
        "e2e-builder": 40,
        "e2e-coverage": 40,
        "e2e-scenarios": 40,
        "e2e-validation": 50,
        "e2e-runtime": 60,
        "live-runtime": 90,
    }

    return sorted(
        [(shard, risk_area, sorted(test_infos, key=lambda item: item.fqcn)) for (shard, risk_area), test_infos in grouped.items()],
        key=lambda item: (risk_order.get(item[1], 70), item[0]),
    )


def print_shard_plan(shards: list[tuple[str, str, list[TestClassInfo]]], include_live_runtime: bool) -> None:
    print("Discovered backend test shards:")
    for shard_name, risk_area, test_infos in shards:
        live = any(test_info.is_live_runtime for test_info in test_infos)
        disposition = "RUN" if include_live_runtime or not live else "SKIP-LIVE"
        print(
            f"- {shard_name:<32} [{disposition}] classes={len(test_infos):>2} risk={risk_area} "
            f"sample={test_infos[0].fqcn}"
        )


def write_init_script(tmp_dir: Path) -> Path:
    init_script = tmp_dir / "omx-test-profile.init.gradle"
    init_script.write_text(
        """
import groovy.json.JsonOutput

allprojects {
    tasks.withType(Test).configureEach { testTask ->
        beforeSuite { desc ->
            if (desc.parent != null && desc.className != null) {
                println("__OMX_TEST_EVENT__ " + JsonOutput.toJson([
                    event: "class_start",
                    className: desc.className,
                    taskPath: testTask.path,
                    timestamp: System.currentTimeMillis()
                ]))
            }
        }
        afterSuite { desc, result ->
            if (desc.parent != null && desc.className != null) {
                println("__OMX_TEST_EVENT__ " + JsonOutput.toJson([
                    event: "class_finish",
                    className: desc.className,
                    taskPath: testTask.path,
                    resultType: String.valueOf(result.resultType),
                    testCount: result.testCount,
                    successfulTestCount: result.successfulTestCount,
                    failedTestCount: result.failedTestCount,
                    skippedTestCount: result.skippedTestCount,
                    durationMs: (result.endTime - result.startTime),
                    timestamp: System.currentTimeMillis()
                ]))
            }
            if (desc.parent == null) {
                println("__OMX_TEST_EVENT__ " + JsonOutput.toJson([
                    event: "task_finish",
                    taskPath: testTask.path,
                    resultType: String.valueOf(result.resultType),
                    testCount: result.testCount,
                    successfulTestCount: result.successfulTestCount,
                    failedTestCount: result.failedTestCount,
                    skippedTestCount: result.skippedTestCount,
                    durationMs: (result.endTime - result.startTime),
                    timestamp: System.currentTimeMillis()
                ]))
            }
        }
    }
}
        """.strip()
        + "\n",
        encoding="utf-8",
    )
    return init_script


def projected_total_minutes(completed_classes: int, elapsed_seconds: float, total_classes: int) -> float | None:
    if completed_classes <= 0 or elapsed_seconds <= 0:
        return None
    average_seconds_per_class = elapsed_seconds / completed_classes
    return (average_seconds_per_class * total_classes) / 60.0


def eta_minutes(completed_classes: int, elapsed_seconds: float, total_classes: int) -> float | None:
    if completed_classes <= 0 or elapsed_seconds <= 0:
        return None
    remaining = max(total_classes - completed_classes, 0)
    average_seconds_per_class = elapsed_seconds / completed_classes
    return (average_seconds_per_class * remaining) / 60.0


def read_sidecar_progress(backend_dir: Path, class_name: str) -> tuple[float | None, str | None]:
    relative_path = SIDE_CAR_PROGRESS_FILES.get(class_name)
    if relative_path is None:
        return None, None
    progress_path = backend_dir / relative_path
    if not progress_path.exists():
        return None, None
    try:
        lines = progress_path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None, None
    latest_line = lines[-1] if lines else None
    try:
        mtime = progress_path.stat().st_mtime
    except OSError:
        mtime = None
    return mtime, latest_line


def format_sidecar_progress(progress_line: str | None) -> str | None:
    if not progress_line:
        return None
    tokens = {}
    for token in progress_line.split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        tokens[key] = value
    completed = tokens.get("completed")
    total = tokens.get("total")
    percent = tokens.get("percent")
    eta = tokens.get("eta")
    scenario = tokens.get("last_scenario")
    if completed and total and percent and eta and scenario:
        return f"sidecar-progress completed={completed}/{total} percent={percent} eta={eta} last_scenario={scenario}"
    return progress_line


def terminate_process(proc: subprocess.Popen[str], label: str) -> None:
    print(f"[abort] {label}: terminating Gradle process", flush=True)
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=10)


def run_shard(
    backend_dir: Path,
    gradle_bin: Path,
    report_dir: Path,
    init_script: Path,
    shard_name: str,
    risk_area: str,
    test_infos: list[TestClassInfo],
    args: argparse.Namespace,
) -> ShardRunReport:
    log_path = report_dir / "logs" / f"{sanitize_name(shard_name)}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    classes = [test_info.fqcn for test_info in test_infos]

    cmd = [
        str(gradle_bin),
        args.gradle_task,
        "--no-daemon",
        "--console=plain",
        "--rerun-tasks",
        "-I",
        str(init_script),
    ]
    for fqcn in classes:
        cmd.extend(["--tests", fqcn])

    print(f"\n[shard] {shard_name} :: {len(classes)} class(es) :: {risk_area}", flush=True)
    print(f"[command] {' '.join(cmd)}", flush=True)

    env = os.environ.copy()
    env.setdefault("TERM", "dumb")
    start = time.monotonic()
    last_completion_time = start
    current_class: str | None = None
    current_class_started_at: float | None = None
    current_class_started_wall_time: float | None = None
    completed_runs: list[ClassRun] = []
    failure_summary: str | None = None
    blocker: str | None = None
    status = "passed"
    last_sidecar_progress_time: float | None = None
    last_sidecar_progress_line: str | None = None

    with log_path.open("w", encoding="utf-8") as log_file:
        proc = subprocess.Popen(
            cmd,
            cwd=backend_dir,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )

        assert proc.stdout is not None
        stdout_fd = proc.stdout.fileno()

        while True:
            ready, _, _ = select.select([stdout_fd], [], [], 1.0)
            if ready:
                line = proc.stdout.readline()
                if not line and proc.poll() is not None:
                    break
                if line:
                    log_file.write(line)
                    log_file.flush()
                    stripped = line.rstrip("\n")
                    if stripped.startswith(EVENT_PREFIX):
                        event = json.loads(stripped[len(EVENT_PREFIX) :])
                        event_name = event.get("event")
                        if event_name == "class_start":
                            current_class = str(event["className"])
                            current_class_started_at = time.monotonic()
                            current_class_started_wall_time = time.time()
                            last_sidecar_progress_time = None
                            last_sidecar_progress_line = None
                        elif event_name == "class_finish":
                            current_class = None
                            current_class_started_at = None
                            current_class_started_wall_time = None
                            last_sidecar_progress_time = None
                            last_sidecar_progress_line = None
                            completed_runs.append(
                                ClassRun(
                                    class_name=str(event["className"]),
                                    duration_ms=int(event.get("durationMs", 0)),
                                    status=str(event.get("resultType", "UNKNOWN")),
                                    test_count=int(event.get("testCount", 0)),
                                    failed_count=int(event.get("failedTestCount", 0)),
                                    skipped_count=int(event.get("skippedTestCount", 0)),
                                )
                            )
                            last_completion_time = time.monotonic()
                            elapsed = last_completion_time - start
                            projected = projected_total_minutes(len(completed_runs), elapsed, len(classes))
                            eta = eta_minutes(len(completed_runs), elapsed, len(classes))
                            throughput = (len(completed_runs) / elapsed) * 60.0 if elapsed > 0 else 0.0
                            print(
                                f"[progress] {shard_name} {len(completed_runs)}/{len(classes)} "
                                f"({len(completed_runs) / len(classes):.0%}) elapsed={format_minutes(elapsed / 60.0)} "
                                f"throughput={throughput:.2f} cls/min eta={format_minutes(eta)} "
                                f"latest={completed_runs[-1].class_name} {completed_runs[-1].duration_ms / 1000:.1f}s "
                                f"status={completed_runs[-1].status}",
                                flush=True,
                            )
                            if completed_runs[-1].failed_count > 0 and failure_summary is None:
                                failure_summary = f"{completed_runs[-1].class_name} reported {completed_runs[-1].failed_count} failed tests"
                        elif event_name == "task_finish":
                            if str(event.get("resultType", "SUCCESS")).upper() not in {"SUCCESS", "PASSED"} and failure_summary is None:
                                failure_summary = (
                                    f"Gradle task finished with {event.get('resultType')} and "
                                    f"{event.get('failedTestCount', 0)} failed tests"
                                )
                    elif any(token in stripped for token in ("FAILED", "FAILURE:", "Exception", "BUILD SUCCESSFUL", "BUILD FAILED")):
                        print(f"[gradle] {stripped}", flush=True)

            if proc.poll() is not None:
                if not ready:
                    break

            now = time.monotonic()
            elapsed = now - start
            completed_count = len(completed_runs)
            projected = projected_total_minutes(completed_count, elapsed, len(classes))
            if (
                projected is not None
                and completed_count >= args.min_completed_classes_for_projection
                and projected > args.max_projected_minutes
            ):
                blocker = (
                    f"projected runtime {projected:.1f}m exceeds budget {args.max_projected_minutes:.1f}m"
                )
                status = "over_budget"
                terminate_process(proc, blocker)
                break

            if completed_count == 0 and elapsed / 60.0 >= args.no_progress_minutes and not (last_sidecar_progress_time is not None and (time.time() - last_sidecar_progress_time) / 60.0 < args.no_progress_minutes):
                blocker = f"no class completed within {args.no_progress_minutes:.1f}m"
                status = "hung"
                terminate_process(proc, blocker)
                break

            if current_class and current_class_started_at is not None:
                sidecar_mtime, sidecar_line = read_sidecar_progress(backend_dir, current_class)
                if sidecar_mtime is not None and current_class_started_wall_time is not None and sidecar_mtime + 1 >= current_class_started_wall_time and (last_sidecar_progress_time is None or sidecar_mtime > last_sidecar_progress_time):
                    last_sidecar_progress_time = sidecar_mtime
                    last_sidecar_progress_line = sidecar_line
                    sidecar_summary = format_sidecar_progress(sidecar_line)
                    if sidecar_summary:
                        print(f"[heartbeat] {current_class} {sidecar_summary}", flush=True)
                active_minutes = (now - current_class_started_at) / 60.0
                sidecar_recent = last_sidecar_progress_time is not None and (time.time() - last_sidecar_progress_time) / 60.0 < args.no_progress_minutes
                if active_minutes >= args.hang_class_minutes and not sidecar_recent:
                    blocker = f"active class {current_class} exceeded {args.hang_class_minutes:.1f}m without finishing"
                    status = "hung"
                    terminate_process(proc, blocker)
                    break
                if completed_count > 0 and (now - last_completion_time) / 60.0 >= args.no_progress_minutes and not sidecar_recent:
                    blocker = (
                        f"no additional class completions for {(now - last_completion_time) / 60.0:.1f}m; "
                        f"active class={current_class}"
                    )
                    status = "hung"
                    terminate_process(proc, blocker)
                    break

        return_code = proc.wait(timeout=10)

    elapsed_total = time.monotonic() - start
    if status == "passed" and return_code != 0:
        status = "failed"
        blocker = blocker or f"Gradle exited with code {return_code}"

    projected = projected_total_minutes(len(completed_runs), elapsed_total, len(classes))
    eta = eta_minutes(len(completed_runs), elapsed_total, len(classes))
    slow_classes = [
        {
            "class_name": class_run.class_name,
            "duration_seconds": round(class_run.duration_ms / 1000.0, 2),
            "status": class_run.status,
            "failed_count": class_run.failed_count,
        }
        for class_run in sorted(completed_runs, key=lambda item: item.duration_ms, reverse=True)[:5]
    ]

    return ShardRunReport(
        name=shard_name,
        risk_area=risk_area,
        class_count=len(classes),
        classes=classes,
        status=status,
        elapsed_seconds=round(elapsed_total, 2),
        completed_classes=len(completed_runs),
        projected_total_minutes=round(projected, 2) if projected is not None else None,
        eta_minutes=round(eta, 2) if eta is not None else None,
        slow_classes=slow_classes,
        active_class_at_stop=current_class,
        blocker=blocker,
        failure_summary=failure_summary,
        log_path=str(log_path),
    )


def sanitize_name(value: str) -> str:
    return value.replace("::", "__").replace("/", "_").replace(" ", "_")


def format_minutes(minutes: float | None) -> str:
    if minutes is None:
        return "n/a"
    if minutes < 1:
        return f"{minutes * 60:.0f}s"
    return f"{minutes:.1f}m"


def build_summary(
    report_dir: Path,
    backend_dir: Path,
    shard_reports: list[ShardRunReport],
    total_shards: int,
) -> ProfileSummary:
    skipped_live = [report for report in shard_reports if report.status == "skipped_live_runtime"]
    blockers: list[str] = []
    for report in shard_reports:
        if report.status in {"failed", "over_budget", "hung"}:
            reason = report.blocker or report.failure_summary or report.status
            blockers.append(f"{report.name}: {reason}")
        elif report.status == "skipped_live_runtime":
            blockers.append(f"{report.name}: skipped live-runtime expansion by policy")

    full_gradlew_test_feasible = not blockers

    all_slow_classes = []
    for report in shard_reports:
        for slow in report.slow_classes:
            all_slow_classes.append({**slow, "shard": report.name})
    all_slow_classes.sort(key=lambda item: item["duration_seconds"], reverse=True)

    recommended_plan = make_recommended_plan(shard_reports, full_gradlew_test_feasible)

    return ProfileSummary(
        generated_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        backend_dir=str(backend_dir),
        shard_count=total_shards,
        executed_shard_count=sum(1 for report in shard_reports if report.status != "skipped_live_runtime"),
        skipped_live_runtime_shard_count=len(skipped_live),
        full_gradlew_test_feasible=full_gradlew_test_feasible,
        blockers=blockers,
        slow_classes=all_slow_classes[:10],
        recommended_verification_plan=recommended_plan,
        shard_reports=shard_reports,
    )


def make_recommended_plan(shard_reports: list[ShardRunReport], full_gradlew_test_feasible: bool) -> list[str]:
    if full_gradlew_test_feasible:
        return [
            "All bounded shards passed within budget; a full ./gradlew test rerun is feasible.",
            "Rerun ./gradlew test once in a clean backend workspace to confirm aggregate behavior.",
            "Keep live-runtime shards separate only if policy/environment still requires opt-in.",
        ]

    recommendations = [
        "Do not rerun plain ./gradlew test yet; keep verification sharded until blockers are cleared.",
    ]
    if any(report.status == "skipped_live_runtime" for report in shard_reports):
        recommendations.append(
            "Continue excluding live-runtime expansion shards from default regression sweeps; run them only with explicit runtime/environment opt-in."
        )
    over_budget = [report for report in shard_reports if report.status == "over_budget"]
    if over_budget:
        recommendations.append(
            "Split over-budget shards further by class or subpackage and isolate the slowest classes before any full-suite rerun."
        )
    failed = [report for report in shard_reports if report.status == "failed"]
    if failed:
        recommendations.append(
            "Fix failing shards first, then rerun only the affected shard(s) plus adjacent risk areas before widening scope."
        )
    hung = [report for report in shard_reports if report.status == "hung"]
    if hung:
        recommendations.append(
            "Investigate hanging classes directly with class-level --tests runs and richer Gradle logging before retrying their parent shard."
        )
    recommendations.append(
        "When blockers are resolved, rerun all non-live bounded shards, then consider a final full-suite pass only if every shard stays under the 45-minute budget envelope."
    )
    return recommendations


def write_reports(summary: ProfileSummary, report_dir: Path) -> tuple[Path, Path]:
    report_dir.mkdir(parents=True, exist_ok=True)
    json_path = report_dir / "backend-test-profile-summary.json"
    md_path = report_dir / "backend-test-profile-summary.md"
    json_path.write_text(json.dumps(asdict(summary), indent=2) + "\n", encoding="utf-8")
    md_path.write_text(render_markdown(summary), encoding="utf-8")
    return json_path, md_path


def render_markdown(summary: ProfileSummary) -> str:
    lines = [
        "# Backend Test Profile Summary",
        "",
        f"- Generated: `{summary.generated_at}`",
        f"- Backend dir: `{summary.backend_dir}`",
        f"- Shards discovered: **{summary.shard_count}**",
        f"- Shards executed: **{summary.executed_shard_count}**",
        f"- Live-runtime shards skipped: **{summary.skipped_live_runtime_shard_count}**",
        f"- Full `./gradlew test` feasible now: **{'yes' if summary.full_gradlew_test_feasible else 'no'}**",
        "",
        "## Blockers",
        "",
    ]
    if summary.blockers:
        lines.extend([f"- {blocker}" for blocker in summary.blockers])
    else:
        lines.append("- None")
    lines.extend([
        "",
        "## Slowest Classes",
        "",
    ])
    if summary.slow_classes:
        for item in summary.slow_classes:
            lines.append(
                f"- `{item['class_name']}` ({item['shard']}) — {item['duration_seconds']}s [{item['status']}]"
            )
    else:
        lines.append("- No completed classes recorded")

    lines.extend([
        "",
        "## Recommended Verification Plan",
        "",
    ])
    lines.extend([f"- {step}" for step in summary.recommended_verification_plan])
    lines.extend([
        "",
        "## Shards",
        "",
        "| Shard | Risk area | Status | Classes | Completed | Elapsed | Projected total | Blocker |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | --- |",
    ])
    for report in summary.shard_reports:
        lines.append(
            "| {name} | {risk} | {status} | {class_count} | {completed} | {elapsed:.1f}s | {projected} | {blocker} |".format(
                name=report.name,
                risk=report.risk_area,
                status=report.status,
                class_count=report.class_count,
                completed=report.completed_classes,
                elapsed=report.elapsed_seconds,
                projected=(f"{report.projected_total_minutes:.1f}m" if report.projected_total_minutes is not None else "n/a"),
                blocker=report.blocker or "",
            )
        )
    lines.append("")
    return "\n".join(lines)


def resolve_gradle_bin(backend_dir: Path, gradle_bin_arg: str) -> Path:
    gradle_bin = Path(gradle_bin_arg)
    if not gradle_bin.is_absolute():
        gradle_bin = backend_dir / gradle_bin
    return gradle_bin.resolve()


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parents[1]
    backend_dir = (repo_root / args.backend_dir).resolve()
    if not backend_dir.is_dir():
        print(f"Backend directory not found: {backend_dir}", file=sys.stderr)
        return 2

    test_root = backend_dir / "src/test/java"
    if not test_root.is_dir():
        print(f"Test root not found: {test_root}", file=sys.stderr)
        return 2

    report_dir = (repo_root / args.report_dir).resolve()
    gradle_bin = resolve_gradle_bin(backend_dir, args.gradle_bin)
    if not gradle_bin.exists():
        print(f"Gradle launcher not found: {gradle_bin}", file=sys.stderr)
        return 2

    classes = discover_test_classes(test_root)
    shards = group_into_shards(classes)

    if args.only_shard:
        allowed = set(args.only_shard)
        shards = [shard for shard in shards if shard[0] in allowed]
    if args.limit_shards:
        shards = shards[: args.limit_shards]

    print_shard_plan(shards, include_live_runtime=args.include_live_runtime)
    if args.dry_run:
        return 0

    shard_reports: list[ShardRunReport] = []
    with tempfile.TemporaryDirectory(prefix="omx-test-profile-") as tmp:
        init_script = write_init_script(Path(tmp))
        for shard_name, risk_area, test_infos in shards:
            if any(test_info.is_live_runtime for test_info in test_infos) and not args.include_live_runtime:
                print(f"\n[skip] {shard_name}: live-runtime expansion excluded by policy", flush=True)
                shard_reports.append(
                    ShardRunReport(
                        name=shard_name,
                        risk_area=risk_area,
                        class_count=len(test_infos),
                        classes=[test_info.fqcn for test_info in test_infos],
                        status="skipped_live_runtime",
                        elapsed_seconds=0.0,
                        completed_classes=0,
                        blocker="skipped live-runtime expansion by policy",
                    )
                )
                continue

            shard_report = run_shard(backend_dir, gradle_bin, report_dir, init_script, shard_name, risk_area, test_infos, args)
            shard_reports.append(shard_report)
            if args.stop_on_failure and shard_report.status not in {"passed", "skipped_live_runtime"}:
                break

    summary = build_summary(report_dir, backend_dir, shard_reports, len(shards))
    json_path, md_path = write_reports(summary, report_dir)
    print(f"\n[report] json={json_path}")
    print(f"[report] md={md_path}")
    print(f"[feasible] full ./gradlew test = {summary.full_gradlew_test_feasible}")
    if summary.blockers:
        for blocker in summary.blockers:
            print(f"[blocker] {blocker}")
    return 0 if summary.full_gradlew_test_feasible else 1


if __name__ == "__main__":
    raise SystemExit(main())
