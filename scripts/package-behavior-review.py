#!/usr/bin/env python3
"""Prepare a reviewable, self-contained CI integration for the owned showcase."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New package directory; never overwrites')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    paths = [Path('scripts/behavior-review.py'), Path('scripts/behavior-gallery.py'),
             Path('docs/cases/behavior-gallery.json')]
    core = Path('experiments/behavior-search')
    paths += [p.relative_to(ROOT) for p in (ROOT / core).rglob('*')
              if p.is_file() and p.suffix in ('.py', '.java', '.json')]
    for name in ('refund-extraction', 'ticket-transfer'):
        fixture = Path('demo') / name
        paths.append(fixture / 'pom.xml')
        paths += [p.relative_to(ROOT) for p in (ROOT / fixture / 'src').rglob('*') if p.is_file()]
    paths += [Path('LICENSE')]
    hashes = {}
    for path in sorted(paths):
        destination = args.output / 'tools/undertow' / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / path, destination)
        hashes[path.as_posix()] = hashlib.sha256(destination.read_bytes()).hexdigest()
    manifest = dict(kind='vendored-behavior-review', source_repository='https://github.com/alpercandemir/undertow',
                    base_commit=subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
                    source_dirty=bool(subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain'])),
                    files=hashes, note='File hashes identify this snapshot; base_commit alone is not a release identity.')
    (args.output / 'tools/undertow/SOURCE.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    component = args.output / 'refund-service'
    component.mkdir()
    shutil.copyfile(ROOT / 'demo/refund-extraction/pom.xml', component / 'pom.xml')
    shutil.copytree(ROOT / 'demo/refund-extraction/src', component / 'src')
    workflow = args.output / '.github/workflows/behavior-review.yml'
    workflow.parent.mkdir(parents=True)
    shutil.copyfile(ROOT / 'docs/examples/showcase-behavior-review.yml', workflow)
    shutil.copyfile(ROOT / 'docs/examples/showcase-behavior-gallery.yml', workflow.with_name('behavior-gallery.yml'))
    print('Prepared integration: ' + str(args.output))


if __name__ == '__main__':
    main()
