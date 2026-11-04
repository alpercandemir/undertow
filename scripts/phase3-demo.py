#!/usr/bin/env python3
"""Exercise the packaged central worker with customer-only snapshots and authored replay.

This uses no GitHub/model credential, registers no App, and makes no live-quality claim.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def canonical(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def git(repository, *arguments):
    return subprocess.check_output([
        "git", "-c", "user.name=Undertow Internal Demo", "-c",
        "user.email=demo@undertow.invalid", "-C", str(repository), *arguments
    ], text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/undertow.jar")
    args = parser.parse_args()
    destination, jar = args.output.resolve(), args.jar.resolve()
    if destination.exists():
        raise ValueError("Choose a new output directory to preserve prior evidence")
    destination.mkdir(parents=True)
    source = destination / "customer-repo"
    shutil.copytree(ROOT / "demo/customer-service", source,
                    ignore=shutil.ignore_patterns("target", ".git"))
    git(source, "init", "-q")
    git(source, "add", ".")
    git(source, "commit", "-qm", "Correct customer payment and tenant contracts")
    base = git(source, "rev-parse", "HEAD")
    source_path = "src/main/java/example/OrderService.java"
    application = source / source_path
    application.write_text(application.read_text().replace(
        "gateway.charge(order.operationKey(), order.amount());",
        "gateway.charge(UUID.randomUUID().toString(), order.amount());"))
    git(source, "add", ".")
    git(source, "commit", "-qm", "Authored unsafe payment-key change")
    head = git(source, "rev-parse", "HEAD")
    config = json.loads((ROOT / "deploy/phase3/service.example.json").read_text())
    policy_files = {f".undertow/{p.name}": p.read_text()
                    for p in sorted((source / ".undertow").iterdir()) if p.is_file()}
    hashes = {
        "config/languages/java/rules.yaml": digest(policy_files[".undertow/rules.yaml"]),
        "config/business-context.yaml": digest(policy_files[".undertow/business-context.yaml"]),
        "CODING-SKILL.md": digest(policy_files[".undertow/guidelines.md"]),
        "@service/execution.json": digest(canonical(config["execution"])),
        "@service/dependency-sources.json": digest(canonical(config["sources"]))
    }
    manifest = dict(review=str(uuid.uuid4()), tenant="internal-authored-demo",
                    files=policy_files, execution=config["execution"], sources=config["sources"],
                    engineDigest="sha256:" + hashlib.sha256(jar.read_bytes()).hexdigest(),
                    effectivePolicy=digest(canonical(dict(sorted(hashes.items())))),
                    policyIdentity=base, executionVersion=config["executionVersion"],
                    snapshot=dict(repositoryId=1, accountId=1, name="internal/customer-demo",
                                  pr=1, head=head, target=base, diffBase=base,
                                  policyCommit=base, branch="main", open=True, draft=False,
                                  internal=True, maintainerAuthor=True, ciIdentity="missing"))
    manifest_file = destination / "manifest.json"
    manifest_file.write_text(json.dumps(manifest, indent=2) + "\n")
    line = next(i for i, text in enumerate(application.read_text().splitlines(), 1)
                if "gateway.charge" in text)
    finding = dict(
        id="authored-payment-key", title="Retried payment gets a new operation key",
        language="java", severity="CRITICAL", category="DATA_INTEGRITY", confidence="HIGH",
        confidence_explanation="Authored fixture; no live model accuracy measurement.",
        evidence_status="INFERRED", guideline_label="BLOCKER", rule_ids=["COMPANY-PAYMENT-001"],
        location=dict(commit=head, path=source_path, snapshot="head", start_line=line, end_line=line),
        trigger="The gateway charges an order, loses the response, and the caller retries.",
        changed_behavior="The payment call now generates a fresh UUID for each attempt.",
        technical_consequence="Gateway deduplication cannot identify the retry as the same operation.",
        business_consequence="One order may be charged twice.",
        assumptions=["The gateway deduplicates by operation key; this is a declared contract."],
        evidence_refs=["e1", "e2", "e3", "e4"],
        recommended_change="Reuse order.operationKey() for all attempts.",
        regression_tests=[dict(setup="Fake gateway with persistent deduplication.",
                               stimulus="Lose the first response and retry the order.",
                               expected_outcome="Equal operation keys and one charge.",
                               level="integration", status="proposed")])
    turns = [{"calls": [
        {"name": "get_diff", "arguments": {}},
        {"name": "read_source", "arguments": dict(snapshot="head", path=source_path,
            start_line=1, end_line=len(application.read_text().splitlines()))},
        {"name": "get_rule", "arguments": {"id": "COMPANY-PAYMENT-001"}},
        {"name": "get_business_context", "arguments": {}}
    ]}, {"review": dict(summary="Authored hosted-worker replay; live quality is unmeasured.",
                        findings=[finding], coverage_notes=["No CI imported; classpath analysis unresolved."])}]
    replay = destination / "replay.json"
    replay.write_text(json.dumps(turns, indent=2) + "\n")
    subprocess.run(["java", "-jar", str(jar), "service-worker", "--repo", str(source),
                    "--manifest", str(manifest_file), "--output", str(destination / "review"),
                    "--mode", "replay", "--replay", str(replay)], check=True)
    print(destination / "review/report.md")


if __name__ == "__main__":
    main()
