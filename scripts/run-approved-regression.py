#!/usr/bin/env python3
"""Separate CI-only executor for a reviewed oracle archive and pinned application sources.

Run on a disposable CI worker without provider/publisher/production credentials. The
plan is trusted host output from `contracts regression-plan`, never model input.
Dependencies and the digest-pinned image must already be prepared by a controlled
stage. This is not a model tool or a claim that all Docker hosts are isolated.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import uuid

MAX_BYTES = 80_000_000


def archive_files(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
        raise ValueError('Archive exceeds limits')
    result = {}
    total = 0
    with tarfile.open(path, 'r:') as archive:
        for index, item in enumerate(archive):
            name = item.name.rstrip('/') if item.isdir() else item.name
            parts = name.split('/')
            if (index >= 5000 or name.startswith(('/', '-')) or '\\' in name
                    or any(part in {'', '.', '..'} for part in parts) or len(name) > 300
                    or any(ord(char) < 32 for char in name) or not (item.isfile() or item.isdir())):
                raise ValueError('Unsafe archive member')
            if item.isdir():
                continue
            total += item.size
            if item.size < 0 or item.size > 1_000_000 or total > MAX_BYTES or name in result:
                raise ValueError('Excessive or duplicate archive member')
            result[name] = archive.extractfile(item).read()
    return result


def prepare(plan, source_archive, oracle_archive, destination):
    if (plan.get('schema_version') != 1 or plan.get('execution') != 'isolated_ci_only'
            or not re.fullmatch(r'[0-9a-f]{40}', plan.get('head_sha', ''))
            or not re.fullmatch(r'[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}', plan.get('image', ''))):
        raise ValueError('Unsupported regression execution plan')
    expected = plan['contract_binding'].get('oracle_artifacts', {})
    if oracle_archive.is_symlink() or not oracle_archive.is_file() or oracle_archive.stat().st_size > MAX_BYTES:
        raise ValueError('Oracle archive must be a bounded regular file')
    digest = hashlib.sha256(oracle_archive.read_bytes()).hexdigest()
    if expected.get(plan['artifact_id']) != digest or digest != plan['artifact_sha256']:
        raise ValueError('Oracle archive is not approved')
    sources, oracle = archive_files(source_archive), archive_files(oracle_archive)
    if set(oracle) != set(plan['oracle_sources']):
        raise ValueError('Oracle archive contents differ from the approved source inventory')
    prefix = plan['project_prefix']
    if prefix and (not prefix.endswith('/') or any(part in {'', '.', '..'} for part in prefix[:-1].split('/'))):
        raise ValueError('Invalid project prefix')
    application = {key: value for key, value in sources.items()
                   if key == prefix + 'pom.xml' or key.startswith(prefix + 'src/main/')}
    if set(application) != set(plan['application_sources']):
        raise ValueError('Application archive does not match pinned source scope')
    for inventory, files in [(plan['application_sources'], application), (plan['oracle_sources'], oracle)]:
        for name, sha in inventory.items():
            if hashlib.sha256(files[name]).hexdigest() != sha:
                raise ValueError('Pinned source content mismatch')
            relative = name[len(prefix):]
            if not (relative == 'pom.xml' or relative.startswith(('src/main/', 'src/test/'))):
                raise ValueError('Unapproved execution path')
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(files[name])


def docker_arguments(plan, project, cache, output, name):
    for path in (project, cache, output):
        if ',' in str(path) or '\n' in str(path):
            raise ValueError('Unsupported bind mount path')
    # This command is host-owned. Neither the manifest nor RPC arguments select shell code.
    command = ('cp -R /input/. /work/project && cd /work/project; '
               'mvn --offline --batch-mode --no-transfer-progress -Dmaven.repo.local=/cache test; '
               'code=$?; mkdir -p /output; '
               'if [ -d target/surefire-reports ]; then cp target/surefire-reports/TEST-*.xml /output/; fi; exit "$code"')
    return ['docker', 'run', '--name', name, '--pull=never', '--network=none', '--read-only',
            '--cpus=1', '--memory=1g', '--memory-swap=1g', '--pids-limit=128', '--cap-drop=ALL',
            '--security-opt=no-new-privileges', '--user=65534:65534', '--workdir=/work',
            '--tmpfs=/work:rw,nosuid,nodev,size=1g,mode=1777', '--tmpfs=/tmp:rw,nosuid,nodev,size=128m,mode=1777',
            '--mount', f'type=bind,src={project},dst=/input,readonly',
            '--mount', f'type=bind,src={cache},dst=/cache,readonly',
            '--mount', f'type=bind,src={output},dst=/output',
            '--env=HOME=/tmp', '--entrypoint=/bin/sh', plan['image'], '-c', command]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['plan', 'subject-archive', 'oracle-archive', 'dependency-cache', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if args.plan.stat().st_size > 1_000_000:
        parser.error('Plan exceeds limits')
    plan = json.loads(args.plan.read_text())
    args.output.mkdir(parents=True, exist_ok=False)
    os.chmod(args.output, 0o777)
    cache = args.dependency_cache.resolve(strict=True)
    if not cache.is_dir():
        parser.error('Prepared dependency repository required')
    name = 'undertow-regression-' + uuid.uuid4().hex
    environment = {key: value for key, value in os.environ.items() if key in {'PATH', 'HOME', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'TMPDIR', 'SYSTEMROOT'}}
    with tempfile.TemporaryDirectory(prefix='undertow-oracle-') as directory:
        project = Path(directory) / 'project'
        project.mkdir()
        prepare(plan, args.subject_archive, args.oracle_archive, project)
        try:
            with (args.output / 'executor.log').open('wb') as log:
                run = subprocess.run(docker_arguments(plan, project.resolve(), cache, args.output.resolve(), name),
                                     env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=120)
            if run.returncode not in (0, 1):
                return 1
            # The importer validates individual outcomes. An exit 1 alone never means reproduced behavior.
            record = dict(schema_version=1, head_sha=plan['head_sha'],
                          oracle_artifacts={plan['artifact_id']: plan['artifact_sha256']}, image=plan['image'])
            with (args.output / 'oracle-execution.json').open('x') as stream:
                stream.write(json.dumps(record, indent=2) + '\n')
            return run.returncode
        finally:
            subprocess.run(['docker', 'rm', '--force', name], env=environment,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)


if __name__ == '__main__':
    raise SystemExit(main())
