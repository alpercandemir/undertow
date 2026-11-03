#!/usr/bin/env python3
"""Run a reviewed tenant-filter mutation with unchanged negative tests in four clean copies."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('ci_formats', ROOT / 'scripts/ci-formats.py')
FORMATS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FORMATS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    rows = []
    for mutation in (False, True):
        for repeat in range(2):
            project = args.output / f'{"unsafe" if mutation else "corrected"}-{repeat}'
            shutil.copytree(ROOT / 'demo/customer-service', project,
                            ignore=shutil.ignore_patterns('target', '.undertow', '.env*'))
            source = project / 'src/main/java/example/OrderService.java'
            if mutation:
                original = source.read_text()
                needle = 'orders.get(new ScopedId(authenticatedTenant, orderId))'
                if original.count(needle) != 1:
                    raise ValueError('Reviewed tenant mutation no longer applies')
                source.write_text(original.replace(needle, 'orders.values().stream().filter(order -> order.id().equals(orderId)).findFirst().orElse(null)'))
            environment = {key: value for key, value in os.environ.items() if key in {'PATH', 'HOME', 'JAVA_HOME', 'TMPDIR', 'LANG'}}
            with (project / 'build-output.txt').open('wb') as log:
                run = subprocess.run(['mvn', '--offline', '--batch-mode', '--no-transfer-progress', '-f', str(project / 'pom.xml'),
                                      '-Dtest=OrderServiceTest#tenantCannotTriggerAnotherTenantsPayment', 'test'],
                                     stdout=log, stderr=subprocess.STDOUT, env=environment, timeout=180)
            report = project / 'target/surefire-reports/TEST-example.OrderServiceTest.xml'
            cases = FORMATS.junit(report.read_bytes()) if report.is_file() else []
            expected = 'failed' if mutation else 'passed'
            accepted = (len(cases) == 1 and cases[0]['name'] == 'example.OrderServiceTest.tenantCannotTriggerAnotherTenantsPayment'
                        and cases[0]['status'] == expected and (run.returncode != 0 if mutation else run.returncode == 0))
            rows.append(dict(mutation=mutation, repeat=repeat, accepted=accepted, executions=cases,
                             oracle_sha256=hashlib.sha256((project / 'src/test/java/example/OrderServiceTest.java').read_bytes()).hexdigest(),
                             subject_sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
    accepted = all(row['accepted'] for row in rows) and len({row['oracle_sha256'] for row in rows}) == 1
    (args.output / 'mutation.json').write_text(json.dumps(dict(schema_version=1, kind='tenant-verifier-sensitivity',
                trusted_ci_import=False, live_model_evaluation=False, accepted=accepted, runs=rows), indent=2) + '\n')
    print('Tenant mutation regression: ' + ('passed' if accepted else 'incomplete/failed'))
    return 0 if accepted else 1


if __name__ == '__main__':
    raise SystemExit(main())
