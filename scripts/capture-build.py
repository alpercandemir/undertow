#!/usr/bin/env python3
"""Trusted build job: record an artifact digest for import with that job's test evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{40}', args.head) or args.artifact.is_symlink():
        parser.error('A pinned commit and regular artifact are required')
    digest = hashlib.sha256()
    with args.artifact.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            digest.update(chunk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(schema_version=1, head_sha=args.head,
                                        artifact_sha256=digest.hexdigest()), indent=2) + '\n')


if __name__ == '__main__':
    main()
