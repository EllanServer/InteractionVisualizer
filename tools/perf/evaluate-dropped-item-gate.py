#!/usr/bin/env python3
"""Evaluate the formal dropped-item section-candidate runtime gate."""

from __future__ import annotations

import argparse
import csv
import json
import os
from pathlib import Path
import statistics
import sys
import tempfile
from typing import Any


SCENARIO = "dropped-items"
AB_FACTOR = "dropped-item-section-candidates"


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


def load_runs(manifest_path: Path, evidence_root: Path,
              expected_items: int, expected_nearby_items: int) -> list[dict[str, Any]]:
    runs: list[dict[str, Any]] = []
    evidence_root_resolved = evidence_root.resolve()
    with manifest_path.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
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
            if provenance.get("itemCount") != expected_items:
                raise ValueError(f"{provenance_path}: itemCount provenance drifted")
            if provenance.get("droppedNearbyItemCount") != expected_nearby_items:
                raise ValueError(f"{provenance_path}: nearby-item provenance drifted")
            variant = row.get("Variant")
            if variant not in {"A", "B"}:
                raise ValueError(f"manifest contains invalid variant {variant!r}")
            runs.append({
                "runId": row.get("RunId"),
                "variant": variant,
                "sourceOwned": boolean_field(
                    metrics, "droppedSourceOwnedSectionCandidates", metrics_path),
                "trackedItemsMax": integer_field(metrics, "droppedTrackedItemsMax", metrics_path),
                "labelsMax": integer_field(metrics, "droppedLabelsMax", metrics_path),
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


def evaluate(manifest_path: Path, evidence_root: Path, expected_items: int,
             expected_nearby_items: int, output_path: Path) -> dict[str, Any]:
    if expected_items < 1 or not 1 <= expected_nearby_items <= expected_items:
        raise ValueError("expected global/local item counts are invalid")
    dropped = load_analysis_result(evidence_root, "droppedItemMs")
    p95 = load_analysis_result(evidence_root, "msptP95")
    p99 = load_analysis_result(evidence_root, "msptP99")
    runs = load_runs(manifest_path, evidence_root, expected_items, expected_nearby_items)
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
        "droppedItemMedianRatioAtMost0_50": dropped["medianBRatioToA"] <= 0.50,
        "droppedItemCiExcludesRegression": dropped["ratioBootstrap95Ci"][1] < 1.0,
        "msptP95CiUpperAtMost1_02": p95["ratioBootstrap95Ci"][1] <= 1.02,
        "msptP99CiUpperAtMost1_05": p99["ratioBootstrap95Ci"][1] <= 1.05,
        "allRunsRetainedGlobalPopulation": all(
            run["trackedItemsMax"] == expected_items for run in runs),
        "allRunsRenderedNearbyPopulation": all(
            run["labelsMax"] == expected_nearby_items for run in runs),
        "candidateHasZeroFullScans": all(
            run["fullScanCandidates"] == 0 for run in runs if run["variant"] == "B"),
        "baselineExercisedFullScans": all(
            run["fullScanCandidates"] > 0 for run in runs if run["variant"] == "A"),
        "candidateTreatmentIsolated": all(
            run["sourceOwned"] is (run["variant"] == "B") for run in runs),
        "candidateSpatialCandidatesAtMost10PercentOfBaseline": candidate_spatial_ratio <= 0.10,
        "candidateViewerChecksAtMost10PercentOfBaseline": candidate_viewer_ratio <= 0.10,
    }
    formal_complete = all(
        result.get("formalComplete") is True for result in (dropped, p95, p99))
    passed = formal_complete and all(checks.values())
    gate = {
        "schemaVersion": 2,
        "scenario": SCENARIO,
        "abFactor": AB_FACTOR,
        "formalComplete": formal_complete,
        "serverRuntimeGatePassed": passed,
        "workload": {
            "trackedItems": expected_items,
            "nearbyLabels": expected_nearby_items,
        },
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
                f"(95% CI `{dropped['ratioBootstrap95Ci']}`).\n\n")
            stream.write(
                f"Candidate/full-scan ratio: `{candidate_spatial_ratio:.6f}`.\n\n")
            stream.write(
                f"MSPT p95 CI upper: `{p95['ratioBootstrap95Ci'][1]}`; "
                f"p99 CI upper: `{p99['ratioBootstrap95Ci'][1]}`.\n")
    return gate


def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        def analysis(metric: str, ratio: float, interval: list[float]) -> dict[str, Any]:
            result = {
                "scenario": SCENARIO,
                "metric": metric,
                "abFactor": AB_FACTOR,
                "formalComplete": True,
                "medianBRatioToA": ratio,
                "ratioBootstrap95Ci": interval,
            }
            return {
                "schemaVersion": 2,
                "metric": metric,
                "abFactor": AB_FACTOR,
                "results": [result],
            }

        valid = analysis("droppedItemMs", 0.40, [0.35, 0.45])
        for metric, document in {
            "droppedItemMs": valid,
            "msptP95": analysis("msptP95", 0.99, [0.98, 1.01]),
            "msptP99": analysis("msptP99", 1.00, [0.99, 1.04]),
        }.items():
            (root / f"{metric}.analysis.json").write_text(
                json.dumps(document), encoding="utf-8")
        parsed = load_analysis_result(root, "droppedItemMs")
        assert parsed["scenario"] == SCENARIO

        manifest_path = root / "abba-manifest.csv"
        with manifest_path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(
                stream, fieldnames=["Scenario", "Variant", "RunId", "SourcePath"])
            writer.writeheader()
            for index in range(12):
                variant = "A" if index % 4 in {0, 3} else "B"
                run_id = f"self_test_{variant}_{index + 1:02d}"
                run_root = root / run_id
                run_root.mkdir()
                metrics = {
                    "droppedSourceOwnedSectionCandidates": variant == "B",
                    "droppedTrackedItemsMax": 2_048,
                    "droppedLabelsMax": 128,
                    "droppedFullScanCandidates": 204_800 if variant == "A" else 0,
                    "droppedSpatialCandidates": 12_800,
                    "droppedViewerDistanceChecks": 12_800,
                }
                (run_root / "iv-perf.json").write_text(
                    json.dumps(metrics), encoding="utf-8")
                (run_root / "run-manifest.json").write_text(json.dumps({
                    "itemCount": 2_048,
                    "droppedNearbyItemCount": 128,
                }), encoding="utf-8")
                writer.writerow({
                    "Scenario": SCENARIO,
                    "Variant": variant,
                    "RunId": run_id,
                    "SourcePath": f"{run_id}/iv-perf.json",
                })
        summary = os.environ.pop("GITHUB_STEP_SUMMARY", None)
        try:
            gate = evaluate(manifest_path, root, 2_048, 128, root / "gate.json")
        finally:
            if summary is not None:
                os.environ["GITHUB_STEP_SUMMARY"] = summary
        assert gate["serverRuntimeGatePassed"] is True
        assert gate["ratios"]["candidateSpatialToBaselineFullScan"] == 0.0625

        invalid = dict(valid)
        invalid.pop("results")
        (root / "droppedItemMs.analysis.json").write_text(
            json.dumps(invalid), encoding="utf-8")
        try:
            load_analysis_result(root, "droppedItemMs")
        except ValueError as exception:
            assert "exactly one analysis result" in str(exception)
        else:
            raise AssertionError("analysis parser accepted a missing schema-v2 results envelope")
    print(json.dumps({"passed": True, "analysisSchemaVersion": 2}, separators=(",", ":")))


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
