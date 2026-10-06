#!/usr/bin/env python3
"""Wrap already-produced CI evidence without executing any reviewed code."""

import argparse
import hashlib
import json
from pathlib import Path

MAX_EVIDENCE_BYTES = 1_000_000


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-tree", type=Path)
    parser.add_argument("--head-tree", type=Path)
    args = parser.parse_args()

    artifacts = []
    for snapshot, path in [("base", args.base_tree), ("head", args.head_tree)]:
        if path is None:
            continue
        if path.stat().st_size > MAX_EVIDENCE_BYTES:
            raise ValueError("Evidence too large")
        content = json.loads(path.read_text(encoding="utf-8"))
        canonical = json.dumps(content, separators=(",", ":"), ensure_ascii=False)
        artifacts.append({
            "snapshot": snapshot,
            "kind": "dependency_tree",
            "content": content,
            "sha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
        })

    bundle = {
        "schema_version": 1,
        "base_sha": args.base,
        "head_sha": args.head,
        "artifacts": artifacts,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bundle, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
