#!/usr/bin/env python3
"""Run a credential-free client showcase using authored replay fixtures."""

import argparse
import json
import subprocess
from pathlib import Path

from importlib.util import module_from_spec, spec_from_file_location

ROOT = Path(__file__).resolve().parents[1]
CURATED = [
    "retry-key-unsafe", "retry-key-corrected",
    "money-rounding-unsafe", "money-rounding-corrected",
    "transaction-this-unsafe", "database-unique-removal",
    "log-token-unsafe", "http-timeout-unsafe",
    "dependency-major-inventory", "record-benign",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="Run all 40 fixtures")
    parser.add_argument("--case", action="append", choices=sorted(
        path.stem for path in (ROOT / "evals/expected").glob("*.json")
    ), help="Select a case; repeat for multiple cases")
    parser.add_argument("--output", type=Path, required=True,
                        help="New directory; existing artifacts are preserved")
    parser.add_argument("--java", default="java", help="JDK 25 java executable")
    args = parser.parse_args()
    if args.all and args.case:
        parser.error("Choose --all or --case")
    jar = ROOT / "target/undertow.jar"
    if not jar.is_file():
        parser.error("Build first with JDK 25: mvn verify")
    destination = args.output.resolve()
    if destination.exists():
        parser.error("Output already exists; choose a new directory")
    cases = (sorted(path.stem for path in (ROOT / "evals/expected").glob("*.json"))
             if args.all else list(dict.fromkeys(args.case or CURATED)))
    spec = spec_from_file_location("prepare_demo", ROOT / "scripts/prepare-demo.py")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    destination.mkdir(parents=True)
    index = destination / "index.md"
    lines = ["# Undertow client showcase", "",
             "Authored offline replay: demonstrates harness behavior, not live-model accuracy.",
             "Regression tests in review reports are proposed; they are not executed by review.", "",
             "| Scenario | Expected finding | Report status | Findings | Artifacts |",
             "|---|---|---|---|---|"]
    index.write_text("\n".join(lines) + "\n", encoding="utf-8")
    for case in cases:
        repository = destination / "snapshots" / case
        info = module.prepare(case, repository)
        output = destination / "reports" / case
        print(f"Running {case}", flush=True)
        subprocess.run([
            args.java, "-jar", str(jar), "review", "--repo", info["repo"],
            "--base", info["base"], "--head", info["head"],
            "--replay", info["replay"], "--output", str(output),
        ], check=True, timeout=360)
        report = json.loads((output / "report.json").read_text(encoding="utf-8"))
        expected = info["label"]["unsafe"]
        matched = any(info["label"]["rule_id"] in finding["rule_ids"]
                      for finding in report["findings"])
        if report["status"] == "failed" or matched != expected or (
                not expected and report["findings"]):
            raise RuntimeError(f"Unexpected replay result for {case}; inspect {output}")
        lines.append(f"| {case} | {'Yes' if expected else 'No'} | {report['status']} | "
                     f"{len(report['findings'])} | [Review](reports/{case}/report.md) · "
                     f"[Evidence](reports/{case}/evidence.json) |")
        index.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Open {index}")


if __name__ == "__main__":
    main()
