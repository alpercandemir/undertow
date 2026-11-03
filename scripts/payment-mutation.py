#!/usr/bin/env python3
"""Exercise one reviewed payment-key mutation in independent copies of the bundled demo.

This developer/CI regression runner is not a model tool or a generic repository executor.
It needs a prepared Maven dependency cache. CI must supply its own disposable isolation.
Output measures this verifier's sensitivity, not live AI quality or trusted import provenance.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("ci_formats", ROOT / "scripts/ci-formats.py")
FORMATS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FORMATS)
TEST = "dev.undertow.demo.PaymentBehaviorTest.lostResponseRetriesTheSameLogicalPayment"
ADAPTER = "src/main/java/dev/undertow/demo/PaymentGatewayAdapter.java"
TEST_SOURCE = "src/test/java/dev/undertow/demo/PaymentBehaviorTest.java"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    rows = []
    for variant in ("corrected", "changed-retry-key"):
        for repetition in range(2):
            project = args.output / f"{variant}-{repetition}"
            shutil.copytree(ROOT / "demo/order-service", project,
                            ignore=shutil.ignore_patterns("target", ".undertow", ".env", ".env.*"))
            if variant != "corrected":
                adapter = project / ADAPTER
                text = adapter.read_text()
                needle = "return gateway.charge(operationKey, money);"
                if text.count(needle) != 2:
                    raise ValueError("Reviewed mutation no longer matches the demo adapter")
                offset = text.rfind(needle)
                adapter.write_text(text[:offset] + text[offset:].replace(
                    needle, 'return gateway.charge(operationKey + "-retry", money);', 1))
            # Do not inherit provider/publisher/production credentials into fixture builds.
            allowed = {"PATH", "JAVA_HOME", "HOME", "TMPDIR", "LANG", "LC_ALL", "SYSTEMROOT"}
            environment = {key: value for key, value in os.environ.items() if key in allowed}
            with (project / "build-output.txt").open("wb") as log:
                run = subprocess.run(["mvn", "--offline", "--batch-mode", "--no-transfer-progress",
                                      "-f", str(project / "pom.xml"), "-Dspotless.skip=true",
                                      "-Dtest=PaymentBehaviorTest#lostResponseRetriesTheSameLogicalPayment", "test"],
                                     stdout=log, stderr=subprocess.STDOUT, timeout=180, env=environment)
            report = project / "target/surefire-reports/TEST-dev.undertow.demo.PaymentBehaviorTest.xml"
            cases = FORMATS.junit(report.read_bytes()) if report.is_file() else []
            matches = [case for case in cases if case["name"] == TEST]
            expected = "passed" if variant == "corrected" else "failed"
            valid = (len(cases) == len(matches) == 1 and matches[0]["status"] == expected
                     and (run.returncode == 0 if expected == "passed" else run.returncode != 0))
            rows.append(dict(variant=variant, repetition=repetition, expected=expected,
                             executions=cases, accepted=valid,
                             oracle_sha256=hashlib.sha256((project / TEST_SOURCE).read_bytes()).hexdigest(),
                             subject_sha256=hashlib.sha256((project / ADAPTER).read_bytes()).hexdigest()))
    result = dict(schema_version=1, kind="approved-verifier-mutation-regression",
                  live_model_evaluation=False, trusted_ci_import=False,
                  accepted=all(row["accepted"] for row in rows), runs=rows)
    if len({row["oracle_sha256"] for row in rows}) != 1:
        result["accepted"] = False
    (args.output / "mutation.json").write_text(json.dumps(result, indent=2) + "\n")
    print("Payment-key mutation regression: " + ("passed" if result["accepted"] else "incomplete/failed"))
    return 0 if result["accepted"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
