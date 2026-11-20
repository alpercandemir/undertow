#!/usr/bin/env python3
"""Build the portable change-review zip and its SHA-256 checksum."""

import argparse
from hashlib import sha256
from pathlib import Path
import re
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
FILES = (
    "LICENSE",
    "SECURITY.md",
    "docs/change-review.md",
    "docs/change-review-evaluation.md",
    "docs/examples/change-review.yml",
    "scripts/change-review.py",
    "scripts/change-review/Inspect.java",
    "scripts/undertow",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = (ROOT / "scripts/change-review.py").read_text(encoding="utf-8")
    version = re.search(r'^VERSION = "([0-9.]+(?:-[a-z0-9.]+)?)"$', source, re.MULTILINE)[1]
    name = f"undertow-change-review-{version}"
    args.output.mkdir(parents=True, exist_ok=False)
    archive = args.output / f"{name}.zip"
    with ZipFile(archive, "x") as bundle:
        for filename in FILES:
            info = ZipInfo(f"{name}/{filename}")
            info.create_system = 3
            info.external_attr = (0o755 if filename == "scripts/undertow" else 0o644) << 16
            bundle.writestr(info, (ROOT / filename).read_bytes(), compress_type=ZIP_DEFLATED)
    checksum = archive.with_suffix(".zip.sha256")
    checksum.write_text(f"{sha256(archive.read_bytes()).hexdigest()}  {archive.name}\n", encoding="utf-8")
    print(archive)


if __name__ == "__main__":
    main()
