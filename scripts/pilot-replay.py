#!/usr/bin/env python3
"""Explicit maintainer-only authored demonstration, bound to actual pinned PR source."""

import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASES = {"retry-key-unsafe", "retry-key-corrected", "tenant-unsafe", "tenant-corrected"}


def build(case, metadata, source, path="src/Subject.java"):
    if path.startswith(("/", "-")) or ".." in path.split("/") or ":" in path or "\\" in path:
        raise ValueError("Unsafe pilot source path")
    def read(commit):
        process = subprocess.run(["git", "--no-replace-objects", "-C", str(source),
                                  "show", commit + ":" + path],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=20)
        if process.returncode:
            raise ValueError("Pilot source missing at pinned commit")
        return process.stdout
    head = read(metadata["head_sha"])
    base = read(metadata["analysis_diff_base"])
    calls = [{"name": "get_diff", "arguments": {}},
             {"name": "read_source", "arguments": {"snapshot": "head", "path": path, "start_line": 1, "end_line": len(head.split("\n"))}},
             {"name": "read_source", "arguments": {"snapshot": "base", "path": path, "start_line": 1, "end_line": len(base.split("\n"))}}]
    unsafe = case.endswith("unsafe")
    tenant = case.startswith("tenant")
    rule = "JAVA-TENANT-001" if tenant else "JAVA-RETRY-001"
    calls += [{"name": "get_rule", "arguments": {"id": rule}}, {"name": "get_business_context", "arguments": {}}]
    findings = []
    if unsafe:
        line = next((i for i, value in enumerate(head.splitlines(), 1)
                     if ("orders.find(orderId)" in value if tenant else "UUID.randomUUID()" in value)), None)
        if line is None:
            raise ValueError("Authored unsafe case does not match pinned source; use the corrected scenario")
        findings = [dict(id="tenant-isolation" if tenant else "payment-retry", title="Tenant isolation lost" if tenant else "Retry creates a second payment identity",
                         language="java", severity="HIGH", category="SECURITY" if tenant else "DATA_INTEGRITY",
                         confidence="HIGH", confidence_explanation="Authored replay against a synthetic declared contract; this is not a live model result.",
                         evidence_status="INFERRED", guideline_label="BLOCKER", rule_ids=[rule],
                         location=dict(commit=metadata["head_sha"], path=path, snapshot="head", start_line=line, end_line=line),
                         trigger="Another tenant guesses an order ID." if tenant else "Gateway commits a charge, loses the response, and the caller retries.",
                         changed_behavior="Tenant predicate removed." if tenant else "A new operation key is generated for every attempt.",
                         technical_consequence="Repository lookup crosses tenant boundaries." if tenant else "Provider sees retries as distinct charges.",
                         business_consequence="Cross-tenant order exposure." if tenant else "Customer may be debited twice.",
                         assumptions=["Synthetic collaborator follows the documented contract."], evidence_refs=["e1", "e2", "e3", "e4", "e5"],
                         recommended_change="Bind authenticated tenant ID." if tenant else "Reuse the operation key.",
                         regression_tests=[dict(setup="Controlled repository/gateway", stimulus="Repeat the triggering request", expected_outcome="No cross-tenant read" if tenant else "Equal keys and one charge", level="integration", status="proposed")])]
    return [{"calls": calls}, {"review": dict(findings=findings,
                  summary="AUTHORED REPLAY: " + case + ". Live-model quality is unmeasured.",
                  coverage_notes=["Synthetic collaborator contract; classpath semantics and CI execution evidence unavailable."])}]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", choices=sorted(CASES), required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--path")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(build(args.case, json.loads(args.metadata.read_text()), args.source, args.path or ("src/TenantOrders.java" if args.case.startswith("tenant") else "src/Subject.java")), indent=2) + "\n")


if __name__ == "__main__":
    main()
