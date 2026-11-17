#!/usr/bin/env python3
"""Verify the historical archive and ensure retired code stays out of the active JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / 'archive/legacy-platform-2026-11-17'


def check_archive():
    manifest = json.loads((ARCHIVE / 'manifest.json').read_text())
    path = ARCHIVE / 'source.tar.gz'
    if hashlib.sha256(path.read_bytes()).hexdigest() != manifest['archive_sha256']:
        raise ValueError('Historical archive digest changed')
    with tarfile.open(path, 'r:gz') as source:
        members = source.getmembers()
        if len(members) != len(manifest['files']) or {m.name for m in members} != set(manifest['files']):
            raise ValueError('Historical archive inventory changed')
        for member in members:
            if not member.isfile() or Path(member.name).is_absolute() or '..' in Path(member.name).parts:
                raise ValueError('Invalid historical archive entry')
            expected = manifest['files'][member.name]
            if (member.mode != int(expected['mode'], 8)
                    or hashlib.sha256(source.extractfile(member).read()).hexdigest() != expected['sha256']):
                raise ValueError('Historical archive member changed: ' + member.name)
    retired = json.loads((ARCHIVE / 'retired-paths.json').read_text())
    for name in retired:
        if name not in manifest['files'] or (ROOT / name).exists():
            raise ValueError('Retired source is missing from the archive or active again: ' + name)
    print(f'Archive verified: {len(manifest["files"])} files; {len(retired)} retired paths absent from active source')


def check_jar(path):
    retired = ('dev/undertow/service/', 'dev/undertow/model/', 'dev/undertow/harness/',
               'dev/undertow/findings/', 'dev/undertow/github/', 'dev/undertow/policy/',
               'dev/undertow/changes/', 'dev/undertow/languages/', 'service/',
               'dev/undertow/contracts/ReleaseAssurance', 'dev/undertow/contracts/RolloutAssurance',
               'dev/undertow/contracts/AssurancePolicy', 'dev/undertow/contracts/PilotStudy',
               'dev/undertow/cli/ModelClientFactory', 'dev/undertow/cli/AssuranceCommands',
               'dev/undertow/tools/ToolRegistry', 'dev/undertow/tools/SafeHttp',
               'dev/undertow/cli/Undertow$')
    removed_libraries = ('h2-', 'jsoup-', 'javaparser-', 'google-genai-')
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        for name in names:
            relative = name.removeprefix('BOOT-INF/classes/')
            if any(relative.startswith(prefix) for prefix in retired):
                raise ValueError('Retired implementation packaged: ' + name)
            if name.startswith('BOOT-INF/lib/') and any(Path(name).name.startswith(prefix) for prefix in removed_libraries):
                raise ValueError('Retired dependency packaged: ' + name)
        for name in ('dev/undertow/cli/ExploreCommand.class', 'dev/undertow/contracts/ContractVerifier.class',
                     'dev/undertow/contracts/ContractMcp.class', 'behavior-search/search.py'):
            if 'BOOT-INF/classes/' + name not in names:
                raise ValueError('Active capability missing from package: ' + name)
    print('Packaged scope verified: behavior search, contracts and read-only agent integration')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    check_archive()
    if args.jar:
        check_jar(args.jar)


if __name__ == '__main__':
    main()
