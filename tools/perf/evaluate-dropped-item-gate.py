#!/usr/bin/env python3
"""Evaluate the formal dropped-item section-candidate runtime gate."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import sys
import tempfile
from typing import Any, Callable


SCENARIO = "dropped-items"
AB_FACTOR = "dropped-item-section-candidates"
PAPER_VERSION = "26.1.2"
PAPER_CHANNEL = "STABLE"
PAPER_BUILD_ID = 74
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
ANALYSIS_METRICS = ("droppedItemMs", "msptP95", "msptP99")
CANONICAL_WINDOWS = {
    "warmupSeconds": 120,
    "settleSeconds": 20,
    "measureSeconds": 180,
}
FORMAL_RUN_COUNT = 12
FORMAL_SAMPLING_MODE = "paired-adjacent"
FORMAL_PAIR_COUNT = 6
MINIMUM_TARGET_IMPROVEMENT = 0.05
MAXIMUM_TARGET_MEDIAN_RATIO = 1.0 - MINIMUM_TARGET_IMPROVEMENT
MAXIMUM_CANDIDATE_WORK_RATIO = 0.10


def read_json(path: Path) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return document


def load_analysis_result(evidence_root: Path, metric: str) -> dict[str, Any]:
    path = evidence_root / f"{metric}.analysis.json"
    document = read_json(path)
    if document.get("schemaVersion") != 2:
        raise ValueError(f"{path.name} must use analysis schemaVersion=2")
    if document.get("metric") != metric:
        raise ValueError(f"{path.name} metric does not match {metric}")
    if document.get("abFactor") != AB_FACTOR:
        raise ValueError(f"{path.name} A/B factor does not match {AB_FACTOR}")
    results = document.get("results")
    if not isinstance(results, list) or len(results) != 1:
        raise ValueError(f"{path.name} must contain exactly one analysis result")
    result = results[0]
    if not isinstance(result, dict) or result.get("scenario") != SCENARIO:
        raise ValueError(f"{path.name} does not contain the {SCENARIO} result")
    if result.get("metric") != metric or result.get("abFactor") != AB_FACTOR:
        raise ValueError(f"{path.name} result provenance does not match its envelope")
    if result.get("captureMethod") != "none":
        raise ValueError(f"{path.name} must use captureMethod=none for clean performance evidence")
    sha256_field(result, "stackSha256", path)
    artifact_hashes = result.get("artifactSha256")
    if not isinstance(artifact_hashes, dict) or set(artifact_hashes) != {"A", "B"}:
        raise ValueError(f"{path.name} must declare artifactSha256 for variants A and B")
    for variant in ("A", "B"):
        sha256_field(artifact_hashes, variant, path)
    if result.get("runCount") != FORMAL_RUN_COUNT:
        raise ValueError(f"{path.name} must declare runCount={FORMAL_RUN_COUNT}")
    if result.get("samplingMode") != FORMAL_SAMPLING_MODE:
        raise ValueError(
            f"{path.name} must use samplingMode={FORMAL_SAMPLING_MODE}")
    if result.get("pairCount") != FORMAL_PAIR_COUNT:
        raise ValueError(f"{path.name} must declare pairCount={FORMAL_PAIR_COUNT}")
    analysis_runs = result.get("runs")
    if not isinstance(analysis_runs, list) or len(analysis_runs) != FORMAL_RUN_COUNT:
        raise ValueError(f"{path.name} must contain {FORMAL_RUN_COUNT} analysis runs")
    run_ids: set[str] = set()
    for run in analysis_runs:
        if not isinstance(run, dict):
            raise ValueError(f"{path.name} analysis runs must be objects")
        run_id = run.get("runId")
        if not isinstance(run_id, str) or not run_id or run_id in run_ids:
            raise ValueError(f"{path.name} analysis runId values must be unique non-empty strings")
        run_ids.add(run_id)
        sha256_field(run, "sourceSha256", path)
    return result


def integer_field(document: dict[str, Any], field: str, source: Path) -> int:
    value = document.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{source}: {field} must be a non-negative integer")
    return value


def boolean_field(document: dict[str, Any], field: str, source: Path) -> bool:
    value = document.get(field)
    if not isinstance(value, bool):
        raise ValueError(f"{source}: {field} must be a boolean")
    return value


def sha256_field(document: dict[str, Any], field: str, source: Path) -> str:
    value = document.get(field)
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{source}: {field} must be a lowercase SHA-256")
    return value


def row_sha256(row: dict[str, str], field: str, source: Path) -> str:
    value = row.get(field, "")
    if SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{source}: manifest {field} must be a lowercase SHA-256")
    return value


def row_boolean(row: dict[str, str], field: str, source: Path) -> bool:
    value = row.get(field)
    if value == "true":
        return True
    if value == "false":
        return False
    raise ValueError(f"{source}: manifest {field} must be true or false")


def population(metrics: dict[str, Any], source: Path, prefix: str) -> dict[str, int]:
    summary = {
        "min": integer_field(metrics, f"{prefix}Min", source),
        "max": integer_field(metrics, f"{prefix}Max", source),
        "end": integer_field(metrics, f"{prefix}End", source),
        "sampleCount": integer_field(metrics, f"{prefix}SampleCount", source),
    }
    if summary["sampleCount"] <= 0:
        raise ValueError(f"{source}: {prefix}SampleCount must be positive")
    if not summary["min"] <= summary["end"] <= summary["max"]:
        raise ValueError(f"{source}: {prefix} min/max/end are inconsistent")
    return summary


def validate_clean_provenance(
        provenance: dict[str, Any], provenance_path: Path,
        metrics: dict[str, Any], metrics_path: Path,
        row: dict[str, str], variant: str, run_id: str,
        expected_items: int, expected_nearby_items: int) -> dict[str, Any]:
    if provenance.get("schemaVersion") != 7:
        raise ValueError(f"{provenance_path}: run manifest must use schemaVersion=7")
    expected_fields = {
        "runId": run_id,
        "scenario": SCENARIO,
        "variant": variant,
        "abFactor": AB_FACTOR,
        "paperVersion": PAPER_VERSION,
        "paperChannel": PAPER_CHANNEL,
        "paperBuildId": PAPER_BUILD_ID,
        "itemCount": expected_items,
        "droppedNearbyItemCount": expected_nearby_items,
        "workloadCount": expected_items,
        **CANONICAL_WINDOWS,
    }
    for field, expected in expected_fields.items():
        if provenance.get(field) != expected:
            raise ValueError(
                f"{provenance_path}: {field} provenance drifted: "
                f"{provenance.get(field)!r} != {expected!r}")
    if provenance.get("paperChannel") not in {"STABLE", "BETA", "UNKNOWN"}:
        raise ValueError(f"{provenance_path}: invalid paperChannel provenance")
    integer_field(provenance, "paperBuildId", provenance_path)

    expected_source_owned = variant == "B"
    source_owned = boolean_field(
        provenance, "droppedSourceOwnedSectionCandidates", provenance_path)
    if source_owned is not expected_source_owned:
        raise ValueError(f"{provenance_path}: dropped-item treatment does not match variant {variant}")
    if boolean_field(metrics, "droppedSourceOwnedSectionCandidates", metrics_path) is not source_owned:
        raise ValueError(f"{metrics_path}: dropped-item treatment differs from run manifest")
    if metrics.get("label") != run_id or metrics.get("abFactor") != AB_FACTOR:
        raise ValueError(f"{metrics_path}: run/scenario factor provenance drifted")

    hash_fields = (
        "pluginSha256",
        "paperSha256",
        "clientManifestSha256",
        "configSha256",
        "jvmArgumentsSha256",
        "jvmArgumentsNormalizedSha256",
    )
    hashes = {field: sha256_field(provenance, field, provenance_path) for field in hash_fields}
    if row_sha256(row, "ArtifactSha256", metrics_path) != hashes["pluginSha256"]:
        raise ValueError(f"{metrics_path}: artifact SHA differs from plugin provenance")
    stack_sha256 = row_sha256(row, "StackSha256", metrics_path)
    for row_field, manifest_field in (
        ("ConfigSha256", "configSha256"),
        ("JvmArgumentsSha256", "jvmArgumentsSha256"),
        ("JvmArgumentsNormalizedSha256", "jvmArgumentsNormalizedSha256"),
    ):
        if row_sha256(row, row_field, metrics_path) != hashes[manifest_field]:
            raise ValueError(f"{metrics_path}: manifest {row_field} provenance drifted")
    for field in ("jvmArgumentsSha256", "jvmArgumentsNormalizedSha256"):
        if metrics.get(field) != hashes[field]:
            raise ValueError(f"{metrics_path}: IV_PERF {field} provenance drifted")

    if row.get("Scenario") != SCENARIO or row.get("RunId") != run_id:
        raise ValueError(f"{metrics_path}: CSV scenario/run provenance drifted")
    if row.get("AbFactor") != AB_FACTOR or row.get("CaptureMethod") != "none":
        raise ValueError(f"{metrics_path}: formal manifest must use the canonical factor and captureMethod=none")
    if row_boolean(row, "DroppedSourceOwnedSectionCandidates", metrics_path) is not source_owned:
        raise ValueError(f"{metrics_path}: CSV dropped-item treatment provenance drifted")

    spark = provenance.get("sparkProfile")
    if not isinstance(spark, dict):
        raise ValueError(f"{provenance_path}: missing Spark profile provenance")
    if (spark.get("enabled") is not False
            or spark.get("mode") != "none"
            or spark.get("profileEvidenceReady") is not None
            or spark.get("performanceEvidenceReady") is not True
            or spark.get("metadataPath") is not None
            or spark.get("metadataSha256") is not None):
        raise ValueError(
            f"{provenance_path}: profiler evidence cannot masquerade as clean performance evidence")

    diagnostics = provenance.get("jvmDiagnostics")
    if not isinstance(diagnostics, dict):
        raise ValueError(f"{provenance_path}: missing JVM diagnostics provenance")
    if (diagnostics.get("formalEvidenceReady") is not True
            or diagnostics.get("finalizedAfterServerExit") is not True
            or diagnostics.get("serverStoppedCleanly") is not True):
        raise ValueError(f"{provenance_path}: JVM diagnostics are not formal-evidence ready")
    for field in ("jvmArgumentsSha256", "jvmArgumentsNormalizedSha256"):
        if diagnostics.get(field) != hashes[field]:
            raise ValueError(f"{provenance_path}: JVM diagnostics {field} provenance drifted")
    sha256_field(diagnostics, "metadataSha256", provenance_path)
    gc_log = diagnostics.get("gcSafepointLog")
    if not isinstance(gc_log, dict):
        raise ValueError(f"{provenance_path}: missing GC/safepoint provenance")
    sha256_field(gc_log, "sha256", provenance_path)
    process = diagnostics.get("processCommandLine")
    if not isinstance(process, dict):
        raise ValueError(f"{provenance_path}: missing live JVM command-line provenance")
    if (process.get("formalEvidenceReady") is not True
            or process.get("capturedFromProcCmdline") is not True):
        raise ValueError(f"{provenance_path}: live JVM command line is not formal-evidence ready")
    for field in ("jvmArgumentsSha256", "jvmArgumentsNormalizedSha256"):
        if process.get(field) != hashes[field]:
            raise ValueError(f"{provenance_path}: live JVM command line {field} drifted")
    sha256_field(process, "metadataSha256", provenance_path)

    cache = provenance.get("legacyTextComponentCache")
    if not isinstance(cache, dict):
        raise ValueError(f"{provenance_path}: missing legacy text cache provenance")
    cache_mapping = {
        "disableProperty": "legacyTextComponentCacheDisableProperty",
        "enabled": "legacyTextComponentCache",
        "requests": "legacyTextCacheRequests",
        "misses": "legacyTextCacheMisses",
        "hits": "legacyTextCacheHits",
        "hitRate": "legacyTextCacheHitRate",
        "sameRawFastPaths": "legacyTextSameRawFastPaths",
    }
    for manifest_field, metrics_field in cache_mapping.items():
        if cache.get(manifest_field) != metrics.get(metrics_field):
            raise ValueError(
                f"{provenance_path}: cache {manifest_field}/{metrics_field} provenance drifted")
    if row_boolean(row, "LegacyTextComponentCacheDisableProperty", metrics_path) \
            is not cache.get("disableProperty"):
        raise ValueError(f"{metrics_path}: CSV cache disable-property provenance drifted")
    if row_boolean(row, "LegacyTextComponentCacheEnabled", metrics_path) \
            is not cache.get("enabled"):
        raise ValueError(f"{metrics_path}: CSV cache enabled provenance drifted")
    return {
        **hashes,
        "stackSha256": stack_sha256,
        "sourceOwned": source_owned,
        "sourceSha256": hashlib.sha256(metrics_path.read_bytes()).hexdigest(),
    }


def load_runs(manifest_path: Path, evidence_root: Path,
              expected_items: int, expected_nearby_items: int) -> list[dict[str, Any]]:
    runs: list[dict[str, Any]] = []
    run_ids: set[str] = set()
    evidence_root_resolved = evidence_root.resolve()
    with manifest_path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        for row in reader:
            if row.get("Scenario") != SCENARIO:
                continue
            source_text = row.get("SourcePath")
            if not source_text:
                raise ValueError("manifest row is missing SourcePath")
            metrics_path = (manifest_path.parent / source_text).resolve()
            try:
                metrics_path.relative_to(evidence_root_resolved)
            except ValueError as exception:
                raise ValueError(f"metrics path escapes evidence root: {metrics_path}") from exception
            metrics = read_json(metrics_path)
            provenance_path = metrics_path.with_name("run-manifest.json")
            provenance = read_json(provenance_path)
            variant = row.get("Variant")
            if variant not in {"A", "B"}:
                raise ValueError(f"manifest contains invalid variant {variant!r}")
            run_id = row.get("RunId")
            if not run_id:
                raise ValueError("manifest row is missing RunId")
            if run_id in run_ids:
                raise ValueError(f"manifest contains duplicate RunId {run_id!r}")
            run_ids.add(run_id)
            evidence = validate_clean_provenance(
                provenance, provenance_path, metrics, metrics_path, row,
                variant, run_id, expected_items, expected_nearby_items)
            tracked = population(metrics, metrics_path, "droppedTrackedItems")
            labels = population(metrics, metrics_path, "droppedLabels")
            if tracked["sampleCount"] != labels["sampleCount"]:
                raise ValueError(f"{metrics_path}: tracked/label population sample counts differ")
            runs.append({
                "runId": run_id,
                "variant": variant,
                "sourcePath": source_text,
                "sourceOwned": evidence["sourceOwned"],
                "sourceSha256": evidence["sourceSha256"],
                "stackSha256": evidence["stackSha256"],
                "artifactSha256": evidence["pluginSha256"],
                "paperSha256": evidence["paperSha256"],
                "clientManifestSha256": evidence["clientManifestSha256"],
                "configSha256": evidence["configSha256"],
                "jvmArgumentsSha256": evidence["jvmArgumentsSha256"],
                "jvmArgumentsNormalizedSha256": evidence["jvmArgumentsNormalizedSha256"],
                "trackedItemsMin": tracked["min"],
                "trackedItemsMax": tracked["max"],
                "trackedItemsEnd": tracked["end"],
                "trackedItemsSampleCount": tracked["sampleCount"],
                "labelsMin": labels["min"],
                "labelsMax": labels["max"],
                "labelsEnd": labels["end"],
                "labelsSampleCount": labels["sampleCount"],
                "fullScanCandidates": integer_field(
                    metrics, "droppedFullScanCandidates", metrics_path),
                "spatialCandidates": integer_field(
                    metrics, "droppedSpatialCandidates", metrics_path),
                "viewerDistanceChecks": integer_field(
                    metrics, "droppedViewerDistanceChecks", metrics_path),
            })
    if len(runs) != 12:
        raise ValueError(f"formal dropped-item gate requires 12 runs, found {len(runs)}")
    if sum(run["variant"] == "A" for run in runs) != 6 \
            or sum(run["variant"] == "B" for run in runs) != 6:
        raise ValueError("formal dropped-item gate requires six runs per variant")
    return runs


def single_run_hash(runs: list[dict[str, Any]], field: str, scope: str) -> str:
    values = {run[field] for run in runs}
    if len(values) != 1:
        raise ValueError(f"formal dropped-item runs mix {field} provenance in {scope}")
    return next(iter(values))


def validate_analysis_closure(
        analyses: dict[str, dict[str, Any]], runs: list[dict[str, Any]]) -> dict[str, Any]:
    stack_sha256 = single_run_hash(runs, "stackSha256", "the campaign")
    paper_sha256 = single_run_hash(runs, "paperSha256", "the campaign")
    client_manifest_sha256 = single_run_hash(
        runs, "clientManifestSha256", "the campaign")
    normalized_jvm_sha256 = single_run_hash(
        runs, "jvmArgumentsNormalizedSha256", "the campaign")
    by_variant: dict[str, dict[str, str]] = {}
    for variant in ("A", "B"):
        variant_runs = [run for run in runs if run["variant"] == variant]
        by_variant[variant] = {
            "artifactSha256": single_run_hash(
                variant_runs, "artifactSha256", f"variant {variant}"),
            "configSha256": single_run_hash(
                variant_runs, "configSha256", f"variant {variant}"),
            "jvmArgumentsSha256": single_run_hash(
                variant_runs, "jvmArgumentsSha256", f"variant {variant}"),
        }
    run_by_id = {run["runId"]: run for run in runs}
    expected_run_ids = set(run_by_id)
    expected_config_hashes = sorted({run["configSha256"] for run in runs})
    expected_config_by_variant = {
        variant: [by_variant[variant]["configSha256"]] for variant in ("A", "B")
    }
    expected_jvm_by_variant = {
        variant: [by_variant[variant]["jvmArgumentsSha256"]] for variant in ("A", "B")
    }
    expected_artifacts = {
        variant: by_variant[variant]["artifactSha256"] for variant in ("A", "B")
    }

    for metric, analysis in analyses.items():
        source = Path(f"{metric}.analysis.json")
        if analysis.get("stackSha256") != stack_sha256:
            raise ValueError(f"{source.name}: analysis stackSha256 does not match CSV evidence")
        if analysis.get("artifactSha256") != expected_artifacts:
            raise ValueError(f"{source.name}: analysis artifactSha256 does not match variant evidence")
        if analysis.get("configSha256") != expected_config_hashes:
            raise ValueError(f"{source.name}: analysis configSha256 does not match CSV evidence")
        if analysis.get("configSha256ByVariant") != expected_config_by_variant:
            raise ValueError(
                f"{source.name}: analysis configSha256ByVariant does not match variant evidence")
        if analysis.get("jvmArgumentsSha256ByVariant") != expected_jvm_by_variant:
            raise ValueError(
                f"{source.name}: analysis jvmArgumentsSha256ByVariant does not match variant evidence")
        if analysis.get("jvmArgumentsNormalizedSha256") != normalized_jvm_sha256:
            raise ValueError(
                f"{source.name}: analysis jvmArgumentsNormalizedSha256 does not match run evidence")

        analysis_runs = analysis["runs"]
        analysis_run_ids = {entry["runId"] for entry in analysis_runs}
        if analysis_run_ids != expected_run_ids:
            raise ValueError(f"{source.name}: analysis runId set does not match the 12 CSV runs")
        for entry in analysis_runs:
            run = run_by_id[entry["runId"]]
            if entry.get("sourceSha256") != run["sourceSha256"]:
                raise ValueError(
                    f"{source.name}: analysis sourceSha256 for {run['runId']} "
                    "does not match current IV_PERF evidence")
            expected_run_fields = {
                "variant": run["variant"],
                "abFactor": AB_FACTOR,
                "configSha256": run["configSha256"],
                "jvmArgumentsSha256": run["jvmArgumentsSha256"],
                "jvmArgumentsNormalizedSha256": run["jvmArgumentsNormalizedSha256"],
            }
            for field, expected in expected_run_fields.items():
                if entry.get(field) != expected:
                    raise ValueError(
                        f"{source.name}: analysis {field} for {run['runId']} "
                        "does not match current run evidence")

    return {
        "stackSha256": stack_sha256,
        "paperSha256": paper_sha256,
        "clientManifestSha256": client_manifest_sha256,
        "jvmArgumentsNormalizedSha256": normalized_jvm_sha256,
        "byVariant": by_variant,
    }


def population_matches(run: dict[str, Any], prefix: str, expected: int) -> bool:
    return run[f"{prefix}Min"] == expected \
        and run[f"{prefix}Max"] == expected \
        and run[f"{prefix}End"] == expected \
        and run[f"{prefix}SampleCount"] > 0


def evaluate(manifest_path: Path, evidence_root: Path, expected_items: int,
             expected_nearby_items: int, output_path: Path) -> dict[str, Any]:
    if expected_items < 1 or not 1 <= expected_nearby_items <= expected_items:
        raise ValueError("expected global/local item counts are invalid")
    analyses = {
        metric: load_analysis_result(evidence_root, metric) for metric in ANALYSIS_METRICS
    }
    dropped = analyses["droppedItemMs"]
    p95 = analyses["msptP95"]
    p99 = analyses["msptP99"]
    runs = load_runs(manifest_path, evidence_root, expected_items, expected_nearby_items)
    provenance = validate_analysis_closure(analyses, runs)
    baseline_full_scan = statistics.median(
        run["fullScanCandidates"] for run in runs if run["variant"] == "A")
    candidate_spatial = statistics.median(
        run["spatialCandidates"] for run in runs if run["variant"] == "B")
    candidate_viewer_checks = statistics.median(
        run["viewerDistanceChecks"] for run in runs if run["variant"] == "B")
    if baseline_full_scan <= 0:
        raise ValueError("baseline did not record a positive full-scan population")
    candidate_spatial_ratio = candidate_spatial / baseline_full_scan
    candidate_viewer_ratio = candidate_viewer_checks / baseline_full_scan

    checks = {
        "droppedItemMedianImprovementAtLeast5Percent":
            dropped["medianBRatioToA"] <= MAXIMUM_TARGET_MEDIAN_RATIO,
        "droppedItemBootstrap95CiUpperBelow1_00":
            dropped["ratioBootstrap95Ci"][1] < 1.0,
        "msptP95CiUpperAtMost1_02": p95["ratioBootstrap95Ci"][1] <= 1.02,
        "msptP99CiUpperAtMost1_05": p99["ratioBootstrap95Ci"][1] <= 1.05,
        "allRunsRetainedGlobalPopulation": all(
            population_matches(run, "trackedItems", expected_items) for run in runs),
        "allRunsRenderedNearbyPopulation": all(
            population_matches(run, "labels", expected_nearby_items) for run in runs),
        "candidateHasZeroFullScans": all(
            run["fullScanCandidates"] == 0 for run in runs if run["variant"] == "B"),
        "baselineExercisedFullScans": all(
            run["fullScanCandidates"] > 0 for run in runs if run["variant"] == "A"),
        "candidateTreatmentIsolated": all(
            run["sourceOwned"] is (run["variant"] == "B") for run in runs),
        "candidateSpatialCandidatesAtMost10PercentOfBaseline":
            candidate_spatial_ratio <= MAXIMUM_CANDIDATE_WORK_RATIO,
        "candidateViewerChecksAtMost10PercentOfBaseline":
            candidate_viewer_ratio <= MAXIMUM_CANDIDATE_WORK_RATIO,
    }
    formal_complete = all(
        result.get("formalComplete") is True for result in analyses.values())
    passed = formal_complete and all(checks.values())
    gate = {
        "schemaVersion": 3,
        "scenario": SCENARIO,
        "abFactor": AB_FACTOR,
        "formalComplete": formal_complete,
        "serverRuntimeGatePassed": passed,
        "acceptancePolicy": {
            "targetMetric": "droppedItemMs",
            "minimumMedianImprovementPercent": MINIMUM_TARGET_IMPROVEMENT * 100.0,
            "bootstrap95CiUpperMustBeBelow": 1.0,
            "msptP95CiUpperAtMost": 1.02,
            "msptP99CiUpperAtMost": 1.05,
            "candidateWorkRatioAtMost": MAXIMUM_CANDIDATE_WORK_RATIO,
            "basis": "docs/phase2-performance-validation.md section 7",
        },
        "workload": {
            "trackedItems": expected_items,
            "nearbyLabels": expected_nearby_items,
            **CANONICAL_WINDOWS,
        },
        "provenance": provenance,
        "checks": checks,
        "ratios": {
            "droppedItemMs": {
                "medianBRatioToA": dropped["medianBRatioToA"],
                "ratioBootstrap95Ci": dropped["ratioBootstrap95Ci"],
            },
            "msptP95": {
                "medianBRatioToA": p95["medianBRatioToA"],
                "ratioBootstrap95Ci": p95["ratioBootstrap95Ci"],
            },
            "msptP99": {
                "medianBRatioToA": p99["medianBRatioToA"],
                "ratioBootstrap95Ci": p99["ratioBootstrap95Ci"],
            },
            "candidateSpatialToBaselineFullScan": candidate_spatial_ratio,
            "candidateViewerChecksToBaselineFullScan": candidate_viewer_ratio,
        },
        "runs": runs,
        "scope": "Server runtime gate only; allocation and native-client frame gates remain separate.",
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(gate, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8", newline="\n") as stream:
            stream.write("### Dropped-item section-candidate server gate\n\n")
            stream.write(f"Result: `{'pass' if passed else 'fail'}`.\n\n")
            stream.write(
                f"Workload: `{expected_items}` tracked / `{expected_nearby_items}` nearby labels.\n\n")
            stream.write(
                f"droppedItemMs B/A: `{dropped['medianBRatioToA']}` "
                f"(95% CI `{dropped['ratioBootstrap95Ci']}`; required median "
                f"`<= {MAXIMUM_TARGET_MEDIAN_RATIO:.2f}` and CI upper `< 1.0`).\n\n")
            stream.write(
                f"Candidate/full-scan ratio: `{candidate_spatial_ratio:.6f}` "
                f"(required `<= {MAXIMUM_CANDIDATE_WORK_RATIO:.2f}`).\n\n")
            stream.write(
                f"MSPT p95 CI upper: `{p95['ratioBootstrap95Ci'][1]}`; "
                f"p99 CI upper: `{p99['ratioBootstrap95Ci'][1]}`.\n")
    return gate


def assert_rejected(action: Callable[[], Any], expected_text: str) -> None:
    try:
        action()
    except ValueError as exception:
        if expected_text not in str(exception):
            raise AssertionError(
                f"expected rejection containing {expected_text!r}, got {exception!r}") from exception
    else:
        raise AssertionError(f"expected rejection containing {expected_text!r}")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        hash_values = {
            "pluginA": "a" * 64,
            "pluginB": "6" * 64,
            "paper": "b" * 64,
            "client": "c" * 64,
            "configA": "d" * 64,
            "configB": "5" * 64,
            "jvm": "e" * 64,
            "jvmNormalized": "f" * 64,
            "stack": "1" * 64,
            "metadata": "2" * 64,
            "command": "3" * 64,
            "gc": "4" * 64,
        }
        manifest_path = root / "abba-manifest.csv"
        fieldnames = [
            "Scenario", "Block", "Position", "Variant", "RunId", "AbFactor",
            "DroppedSourceOwnedSectionCandidates",
            "LegacyTextComponentCacheDisableProperty",
            "LegacyTextComponentCacheEnabled",
            "ConfigSha256", "JvmArgumentsSha256", "JvmArgumentsNormalizedSha256",
            "StackSha256", "ArtifactSha256", "CaptureMethod", "SourcePath",
        ]
        manifest_rows: list[dict[str, str]] = []
        run_roots: list[Path] = []
        patterns = ("ABBA", "BAAB", "ABBA")

        def write_manifest() -> None:
            with manifest_path.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=fieldnames)
                writer.writeheader()
                writer.writerows(manifest_rows)

        for block, pattern in enumerate(patterns, start=1):
            for position, variant in enumerate(pattern, start=1):
                run_id = f"self_test_{block}_{position}_{variant}"
                run_root = root / run_id
                run_root.mkdir()
                run_roots.append(run_root)
                config_sha = hash_values[f"config{variant}"]
                plugin_sha = hash_values[f"plugin{variant}"]
                metrics = {
                    "label": run_id,
                    "abFactor": AB_FACTOR,
                    "droppedSourceOwnedSectionCandidates": variant == "B",
                    "droppedTrackedItemsMin": 2_048,
                    "droppedTrackedItemsMax": 2_048,
                    "droppedTrackedItemsEnd": 2_048,
                    "droppedTrackedItemsSampleCount": 180,
                    "droppedLabelsMin": 128,
                    "droppedLabelsMax": 128,
                    "droppedLabelsEnd": 128,
                    "droppedLabelsSampleCount": 180,
                    "droppedFullScanCandidates": 204_800 if variant == "A" else 0,
                    "droppedSpatialCandidates": 12_800,
                    "droppedViewerDistanceChecks": 12_800,
                    "legacyTextComponentCacheDisableProperty": False,
                    "legacyTextComponentCache": True,
                    "legacyTextCacheRequests": 100,
                    "legacyTextCacheMisses": 5,
                    "legacyTextCacheHits": 95,
                    "legacyTextCacheHitRate": 0.95,
                    "legacyTextSameRawFastPaths": 50,
                    "jvmArgumentsSha256": hash_values["jvm"],
                    "jvmArgumentsNormalizedSha256": hash_values["jvmNormalized"],
                }
                (run_root / "iv-perf.json").write_text(
                    json.dumps(metrics), encoding="utf-8")
                run_manifest = {
                    "schemaVersion": 7,
                    "runId": run_id,
                    "scenario": SCENARIO,
                    "paperVersion": PAPER_VERSION,
                    "paperChannel": PAPER_CHANNEL,
                    "paperBuildId": PAPER_BUILD_ID,
                    "variant": variant,
                    "abFactor": AB_FACTOR,
                    "droppedSourceOwnedSectionCandidates": variant == "B",
                    "itemCount": 2_048,
                    "droppedNearbyItemCount": 128,
                    "workloadCount": 2_048,
                    **CANONICAL_WINDOWS,
                    "pluginSha256": plugin_sha,
                    "paperSha256": hash_values["paper"],
                    "clientManifestSha256": hash_values["client"],
                    "configSha256": config_sha,
                    "jvmArgumentsSha256": hash_values["jvm"],
                    "jvmArgumentsNormalizedSha256": hash_values["jvmNormalized"],
                    "legacyTextComponentCache": {
                        "disableProperty": False,
                        "enabled": True,
                        "requests": 100,
                        "misses": 5,
                        "hits": 95,
                        "hitRate": 0.95,
                        "sameRawFastPaths": 50,
                    },
                    "jvmDiagnostics": {
                        "formalEvidenceReady": True,
                        "finalizedAfterServerExit": True,
                        "serverStoppedCleanly": True,
                        "jvmArgumentsSha256": hash_values["jvm"],
                        "jvmArgumentsNormalizedSha256": hash_values["jvmNormalized"],
                        "metadataSha256": hash_values["metadata"],
                        "gcSafepointLog": {"sha256": hash_values["gc"]},
                        "processCommandLine": {
                            "formalEvidenceReady": True,
                            "capturedFromProcCmdline": True,
                            "jvmArgumentsSha256": hash_values["jvm"],
                            "jvmArgumentsNormalizedSha256": hash_values["jvmNormalized"],
                            "metadataSha256": hash_values["command"],
                        },
                    },
                    "sparkProfile": {
                        "enabled": False,
                        "mode": "none",
                        "profileEvidenceReady": None,
                        "performanceEvidenceReady": True,
                        "metadataPath": None,
                        "metadataSha256": None,
                    },
                }
                (run_root / "run-manifest.json").write_text(
                    json.dumps(run_manifest), encoding="utf-8")
                manifest_rows.append({
                    "Scenario": SCENARIO,
                    "Block": str(block),
                    "Position": str(position),
                    "Variant": variant,
                    "RunId": run_id,
                    "AbFactor": AB_FACTOR,
                    "DroppedSourceOwnedSectionCandidates": str(variant == "B").lower(),
                    "LegacyTextComponentCacheDisableProperty": "false",
                    "LegacyTextComponentCacheEnabled": "true",
                    "ConfigSha256": config_sha,
                    "JvmArgumentsSha256": hash_values["jvm"],
                    "JvmArgumentsNormalizedSha256": hash_values["jvmNormalized"],
                    "StackSha256": hash_values["stack"],
                    "ArtifactSha256": plugin_sha,
                    "CaptureMethod": "none",
                    "SourcePath": f"{run_id}/iv-perf.json",
                })
        write_manifest()

        analysis_parameters = {
            "droppedItemMs": (0.90, [0.85, 0.94]),
            "msptP95": (0.99, [0.98, 1.01]),
            "msptP99": (1.00, [0.99, 1.04]),
        }

        def write_analyses() -> None:
            evidence_runs = []
            for row in manifest_rows:
                metrics_path = root / row["SourcePath"]
                evidence_runs.append({
                    "block": int(row["Block"]),
                    "position": int(row["Position"]),
                    "variant": row["Variant"],
                    "runId": row["RunId"],
                    "value": 1.0,
                    "sourcePath": str(metrics_path.resolve()),
                    "sourceSha256": hashlib.sha256(metrics_path.read_bytes()).hexdigest(),
                    "abFactor": AB_FACTOR,
                    "configSha256": row["ConfigSha256"],
                    "jvmArgumentsSha256": row["JvmArgumentsSha256"],
                    "jvmArgumentsNormalizedSha256": row["JvmArgumentsNormalizedSha256"],
                    "legacyTextComponentCacheDisableProperty": False,
                    "legacyTextComponentCacheEnabled": True,
                })
            for metric, (ratio, interval) in analysis_parameters.items():
                result = {
                    "scenario": SCENARIO,
                    "metric": metric,
                    "abFactor": AB_FACTOR,
                    "configSha256": sorted(
                        {row["ConfigSha256"] for row in manifest_rows}),
                    "configSha256ByVariant": {
                        variant: sorted({
                            row["ConfigSha256"] for row in manifest_rows
                            if row["Variant"] == variant
                        }) for variant in ("A", "B")
                    },
                    "jvmArgumentsSha256ByVariant": {
                        variant: sorted({
                            row["JvmArgumentsSha256"] for row in manifest_rows
                            if row["Variant"] == variant
                        }) for variant in ("A", "B")
                    },
                    "jvmArgumentsNormalizedSha256": hash_values["jvmNormalized"],
                    "captureMethod": "none",
                    "formalComplete": True,
                    "stackSha256": hash_values["stack"],
                    "artifactSha256": {
                        variant: next(
                            row["ArtifactSha256"] for row in manifest_rows
                            if row["Variant"] == variant)
                        for variant in ("A", "B")
                    },
                    "runCount": FORMAL_RUN_COUNT,
                    "samplingMode": FORMAL_SAMPLING_MODE,
                    "pairCount": FORMAL_PAIR_COUNT,
                    "medianBRatioToA": ratio,
                    "ratioBootstrap95Ci": interval,
                    "runs": evidence_runs,
                }
                document = {
                    "schemaVersion": 2,
                    "metric": metric,
                    "abFactor": AB_FACTOR,
                    "results": [result],
                }
                (root / f"{metric}.analysis.json").write_text(
                    json.dumps(document), encoding="utf-8")
        write_analyses()

        summary = os.environ.pop("GITHUB_STEP_SUMMARY", None)
        try:
            gate = evaluate(manifest_path, root, 2_048, 128, root / "gate.json")
            assert gate["schemaVersion"] == 3
            assert gate["serverRuntimeGatePassed"] is True
            assert gate["checks"]["droppedItemMedianImprovementAtLeast5Percent"] is True
            assert gate["checks"]["droppedItemBootstrap95CiUpperBelow1_00"] is True
            assert gate["ratios"]["candidateSpatialToBaselineFullScan"] == 0.0625

            analysis_parameters["droppedItemMs"] = (0.96, [0.92, 0.99])
            write_analyses()
            small_effect_gate = evaluate(
                manifest_path, root, 2_048, 128, root / "small-effect-gate.json")
            assert small_effect_gate["serverRuntimeGatePassed"] is False
            assert small_effect_gate["checks"][
                "droppedItemMedianImprovementAtLeast5Percent"] is False
            assert small_effect_gate["checks"][
                "droppedItemBootstrap95CiUpperBelow1_00"] is True

            analysis_parameters["droppedItemMs"] = (0.90, [0.85, 1.01])
            write_analyses()
            uncertain_effect_gate = evaluate(
                manifest_path, root, 2_048, 128, root / "uncertain-effect-gate.json")
            assert uncertain_effect_gate["serverRuntimeGatePassed"] is False
            assert uncertain_effect_gate["checks"][
                "droppedItemMedianImprovementAtLeast5Percent"] is True
            assert uncertain_effect_gate["checks"][
                "droppedItemBootstrap95CiUpperBelow1_00"] is False

            analysis_parameters["droppedItemMs"] = (0.90, [0.85, 0.94])
            write_analyses()

            declining_metrics_path = run_roots[0] / "iv-perf.json"
            declining_metrics = read_json(declining_metrics_path)
            declining_metrics["droppedTrackedItemsMin"] = 2_047
            declining_metrics_path.write_text(json.dumps(declining_metrics), encoding="utf-8")
            write_analyses()
            declining_gate = evaluate(
                manifest_path, root, 2_048, 128, root / "declining-gate.json")
            assert declining_gate["serverRuntimeGatePassed"] is False
            assert declining_gate["checks"]["allRunsRetainedGlobalPopulation"] is False
            declining_metrics["droppedTrackedItemsMin"] = 2_048
            declining_metrics_path.write_text(json.dumps(declining_metrics), encoding="utf-8")
            write_analyses()

            noncanonical_path = run_roots[0] / "run-manifest.json"
            noncanonical = read_json(noncanonical_path)
            noncanonical["warmupSeconds"] = 119
            noncanonical_path.write_text(json.dumps(noncanonical), encoding="utf-8")
            assert_rejected(
                lambda: load_runs(manifest_path, root, 2_048, 128),
                "warmupSeconds provenance drifted")
            noncanonical["warmupSeconds"] = CANONICAL_WINDOWS["warmupSeconds"]
            noncanonical_path.write_text(json.dumps(noncanonical), encoding="utf-8")

            analysis_path = root / "droppedItemMs.analysis.json"
            replaced_analysis = read_json(analysis_path)
            replaced_analysis["results"][0]["runs"][0]["runId"] = "replacement-run"
            analysis_path.write_text(json.dumps(replaced_analysis), encoding="utf-8")
            assert_rejected(
                lambda: evaluate(manifest_path, root, 2_048, 128, root / "bad-run.json"),
                "analysis runId set")
            write_analyses()

            replaced_analysis = read_json(analysis_path)
            replaced_analysis["results"][0]["runs"][0]["sourceSha256"] = "9" * 64
            analysis_path.write_text(json.dumps(replaced_analysis), encoding="utf-8")
            assert_rejected(
                lambda: evaluate(manifest_path, root, 2_048, 128, root / "bad-source.json"),
                "analysis sourceSha256")
            write_analyses()

            manifest_rows[0]["StackSha256"] = "9" * 64
            write_manifest()
            assert_rejected(
                lambda: evaluate(manifest_path, root, 2_048, 128, root / "mixed-stack.json"),
                "mix stackSha256")
            manifest_rows[0]["StackSha256"] = hash_values["stack"]
            write_manifest()

            for field, expected_text in (
                    ("paperSha256", "mix paperSha256"),
                    ("clientManifestSha256", "mix clientManifestSha256")):
                mixed_path = run_roots[0] / "run-manifest.json"
                mixed = read_json(mixed_path)
                original = mixed[field]
                mixed[field] = "9" * 64
                mixed_path.write_text(json.dumps(mixed), encoding="utf-8")
                assert_rejected(
                    lambda: evaluate(
                        manifest_path, root, 2_048, 128, root / f"mixed-{field}.json"),
                    expected_text)
                mixed[field] = original
                mixed_path.write_text(json.dumps(mixed), encoding="utf-8")

            wrong_capture = read_json(analysis_path)
            wrong_capture["results"][0]["captureMethod"] = "spark-cpu"
            analysis_path.write_text(json.dumps(wrong_capture), encoding="utf-8")
            assert_rejected(
                lambda: load_analysis_result(root, "droppedItemMs"), "captureMethod=none")
            write_analyses()

            profiler_manifest_path = run_roots[1] / "run-manifest.json"
            profiler_manifest = read_json(profiler_manifest_path)
            profiler_manifest["sparkProfile"] = {
                "enabled": True,
                "mode": "alloc",
                "profileEvidenceReady": True,
                "performanceEvidenceReady": False,
            }
            profiler_manifest_path.write_text(json.dumps(profiler_manifest), encoding="utf-8")
            assert_rejected(
                lambda: load_runs(manifest_path, root, 2_048, 128),
                "cannot masquerade as clean performance evidence")
        finally:
            if summary is not None:
                os.environ["GITHUB_STEP_SUMMARY"] = summary
    print(json.dumps({
        "passed": True,
        "analysisSchemaVersion": 2,
        "gateSchemaVersion": 3,
        "documentedEffectSizeGate": True,
        "confidenceIntervalImprovementGate": True,
        "canonicalWindowGate": True,
        "analysisEvidenceClosure": True,
        "mixedStackPaperClientRejected": True,
        "populationWindowGate": True,
        "captureMethodGate": True,
        "profilerMasqueradeRejected": True,
    }, separators=(",", ":")))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", nargs="?", type=Path)
    parser.add_argument("evidence_root", nargs="?", type=Path)
    parser.add_argument("expected_items", nargs="?", type=int)
    parser.add_argument("expected_nearby_items", nargs="?", type=int)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        self_test()
        return 0
    required = (
        args.manifest, args.evidence_root, args.expected_items,
        args.expected_nearby_items, args.output,
    )
    if any(value is None for value in required):
        raise SystemExit(
            "manifest, evidence_root, expected_items, expected_nearby_items, and output are required")
    gate = evaluate(
        args.manifest, args.evidence_root, args.expected_items,
        args.expected_nearby_items, args.output)
    if not gate["formalComplete"]:
        raise SystemExit("dropped-item gate is not a complete 12-run campaign")
    if not gate["serverRuntimeGatePassed"]:
        failed = ", ".join(
            name for name, value in gate["checks"].items() if not value)
        raise SystemExit(f"dropped-item server gate failed: {failed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
