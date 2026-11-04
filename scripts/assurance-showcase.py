#!/usr/bin/env python3
"""Execute an offline sample from owned intent through recovery; all external receipts are SIMULATED.

Builds only the small, source-controlled fixture emitted below. No model, GitHub,
production endpoint, or credentials are used. This is a regression showcase, not
real CI provenance, independent human pilot acceptance, or production validation.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('ci_formats', ROOT / 'scripts/ci-formats.py')
FORMATS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FORMATS)
TEST = 'shop.PaymentTest.retryPreservesIdentity'
SOURCE = 'src/main/java/shop/Payment.java'
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>shop</artifactId><version>1</version>
<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
<dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.0.3</version><scope>test</scope></dependency></dependencies>
<build><finalName>shop</finalName><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.5.1</version></plugin>
</plugins></build></project>'''


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n' if not isinstance(value, str) else value)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    repo = output / 'repo'
    repo.mkdir()
    environment = {key: value for key, value in os.environ.items() if key in {'PATH', 'HOME', 'JAVA_HOME', 'TMPDIR', 'LANG'}}
    def git(*arguments):
        return subprocess.check_output(['git', '--no-replace-objects', '-c', 'core.hooksPath=/dev/null',
            '-c', 'user.name=Showcase fixture', '-c', 'user.email=fixture@undertow.invalid', '-C', str(repo), *arguments], text=True).strip()
    def cli(*arguments, expected=0):
        run = subprocess.run(['java', '-jar', str(ROOT / 'target/undertow.jar'), *map(str, arguments)],
                             capture_output=True, text=True, timeout=180, env=environment)
        if run.returncode != expected:
            raise RuntimeError('Showcase command failed: ' + ' '.join(map(str, arguments[:2])) + '\n' + run.stderr[:1500])
        return run.stdout
    write(repo / 'pom.xml', POM)
    write(repo / '.gitignore', 'target/\n')
    write(repo / SOURCE, 'package shop; public final class Payment { public static String retryKey(String key) { return key; } }\n')
    write(repo / 'src/test/java/shop/PaymentTest.java', '''package shop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class PaymentTest { @Test void retryPreservesIdentity() { assertEquals("operation-42", Payment.retryKey("operation-42")); } }
''')
    tenant_source = 'src/main/java/shop/Receipt.java'
    tenant_good = 'package shop; public final class Receipt { public static String read(String tenant) { if (!tenant.equals("north")) throw new IllegalArgumentException("Forbidden"); return "private-receipt"; } }\n'
    write(repo / tenant_source, tenant_good)
    write(repo / 'src/test/java/shop/ReceiptTest.java', '''package shop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
class ReceiptTest { @Test void anotherTenantCannotReadTheReceipt() { assertThrows(IllegalArgumentException.class, () -> Receipt.read("south")); } }
''')
    cli('contracts', 'init', '--repo', repo, '--repository', 'example/assurance-showcase', '--owner', 'synthetic payments owner',
        '--id', 'PAYMENT-RETRY', '--expectation', 'Payment retries preserve the logical operation identity', '--scope', SOURCE,
        '--test-id', TEST, '--test-source', 'src/test/java/shop/PaymentTest.java', '--oracle', 'pom.xml')
    # Extend the owned starter before initial approval; no existing user policy is edited.
    write(repo / '.undertow/contracts.yaml', dict(schema_version=1, repository='example/assurance-showcase', oracle_paths=['pom.xml'],
        contracts=[dict(id='PAYMENT-RETRY', version='1', owner='synthetic payments owner', expectation='Payment retries preserve the logical operation identity',
                        scope=[SOURCE], verifier='junit', tests=[dict(id=TEST, sources=['src/test/java/shop/PaymentTest.java'])]),
                   dict(id='TENANT-BOUNDARY', version='1', owner='synthetic security owner', expectation='A different tenant cannot read a private receipt',
                        scope=[tenant_source], verifier='junit', tests=[dict(id='shop.ReceiptTest.anotherTenantCannotReadTheReceipt', sources=['src/test/java/shop/ReceiptTest.java'])])]))
    write(repo / '.undertow/release.yaml', 'schema_version: 1\nenabled: true\nowner: synthetic release owner\nrequired_contracts: [PAYMENT-RETRY, TENANT-BOUNDARY]\n')
    write(repo / '.undertow/operations.yaml', '''schema_version: 1
owner: synthetic operations owner
environment: showcase
contract_id: PAYMENT-RETRY
minimum_events: 10
minimum_window_seconds: 30
maximum_age_seconds: 120
maximum_detection_seconds: 10
reconciliation_required: true
''')
    git('init', '-q'); git('add', '.'); git('commit', '-qm', 'Approved synthetic intent and corrected implementation')
    baseline = git('rev-parse', 'HEAD'); git('tag', 'baseline')
    common = ['--repo', repo, '--base', baseline, '--policy-commit', baseline, '--target-head', baseline]
    cli('contracts', 'export', *common, '--head', baseline, '--output', output / 'policy-binding.json')
    binding = json.loads((output / 'policy-binding.json').read_text())
    observations = []
    sequence = 1000
    def bundle(head, label, tests, build=None):
        nonlocal sequence
        sequence += 1
        entries = []
        for kind, content in [('tests', tests)] + ([('build', build)] if build else []):
            canonical = json.dumps(content, separators=(',', ':'), ensure_ascii=False).encode()
            entries.append(dict(snapshot='head', kind=kind, content=content, sha256=digest(canonical)))
        origin = dict(repository='example/assurance-showcase', run_id=str(sequence), workflow_id='1', artifact_id=str(sequence), head_sha=head)
        payload = dict(schema_version=2, base_sha=baseline, run_conclusion='success' if all(row['status']=='passed' for row in tests) else 'failure',
                       artifacts=entries, contract_binding=binding, origin=dict(synthetic=True), **origin)
        path, receipt = output / 'fixtures' / (label + '.json'), output / 'fixtures' / (label + '-SIMULATED-receipt.json')
        write(path, payload)
        write(receipt, dict(verified_source='github_actions_api', bundle_sha256=digest(path.read_bytes()),
                           contract_binding=binding, run_conclusion=payload['run_conclusion'], **origin))
        return path, receipt
    def execute(head, label, expected, tenant_expected='passed'):
        project = output / 'builds' / label
        project.mkdir(parents=True)
        for file in git('ls-tree', '-r', '--name-only', head).splitlines():
            if file == 'pom.xml' or file.startswith('src/'):
                content = subprocess.check_output(['git', '--no-replace-objects', '-C', str(repo), 'show', head + ':' + file])
                target = project / file
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
        with (project / 'maven.log').open('wb') as log:
            run = subprocess.run(['mvn', '--offline', '--batch-mode', '--no-transfer-progress', '-f', str(project / 'pom.xml'), 'package'],
                                 stdout=log, stderr=subprocess.STDOUT, timeout=180, env=environment)
        xml = project / 'target/surefire-reports/TEST-shop.PaymentTest.xml'
        tests = FORMATS.junit(xml.read_bytes()) if xml.is_file() else []
        tenant_xml = project / 'target/surefire-reports/TEST-shop.ReceiptTest.xml'
        tenant_tests = FORMATS.junit(tenant_xml.read_bytes()) if tenant_xml.is_file() else []
        if (len(tests) != 1 or tests[0]['name'] != TEST or tests[0]['status'] != expected
                or len(tenant_tests) != 1 or tenant_tests[0]['status'] != tenant_expected
                or (run.returncode == 0) != (expected == tenant_expected == 'passed')):
            raise RuntimeError('Controlled fixture did not produce its expected assertion outcome; inspect ' + str(project / 'maven.log'))
        tests.extend(tenant_tests)
        build = None
        if expected == tenant_expected == 'passed':
            artifact = project / 'target/shop.jar'
            build = dict(schema_version=1, head_sha=head, artifact_sha256=digest(artifact.read_bytes()))
        return bundle(head, label, tests, build), project
    def verify(head, label, inputs, expected):
        destination = output / 'results' / label
        cli('verify', *common, '--head', head, '--ci-bundle', inputs[0], '--ci-receipt', inputs[1], '--output', destination, '--sarif', destination / 'contracts.sarif')
        report = json.loads((destination / 'contracts.json').read_text())
        assert report['verification'] == expected, label
        observations.append(dict(case=label, result=expected, synthetic_external_receipt=True))
        return report
    runs = dict(schema_version=1, baseline=[], candidate=[])
    for repeat in range(2):
        evidence, _ = execute(baseline, f'baseline-{repeat}', 'passed')
        runs['baseline'].append(dict(bundle=str(evidence[0]), receipt=str(evidence[1])))
    write(repo / SOURCE, 'package shop; public final class Payment { public static String retryKey(String key) { return key + "-retry"; } }\n')
    git('add', SOURCE); git('commit', '-qm', 'Seed a payment operation identity violation')
    unsafe = git('rev-parse', 'HEAD'); git('tag', 'violation')
    unsafe_report = None
    for repeat in range(2):
        evidence, _ = execute(unsafe, f'violation-{repeat}', 'failed')
        runs['candidate'].append(dict(bundle=str(evidence[0]), receipt=str(evidence[1])))
        unsafe_report = verify(unsafe, f'violation-{repeat}', evidence, 'unsatisfied')
    write(output / 'reproduction-input.json', runs)
    cli('contracts', 'reproduce', *common, '--head', unsafe, '--runs', output / 'reproduction-input.json',
        '--contract', 'PAYMENT-RETRY', '--test-id', TEST, '--output', output / 'reproduction.json')
    assert json.loads((output / 'reproduction.json').read_text())['conclusion'] == 'reproduced_regression'
    write(repo / SOURCE, 'package shop; public final class Payment { public static String retryKey(String key) { return key; /* correction */ } }\n')
    write(repo / tenant_source, tenant_good.replace('!tenant.equals("north")', 'false'))
    git('add', SOURCE, tenant_source); git('commit', '-qm', 'Seed an independent tenant authorization failure')
    tenant_unsafe = git('rev-parse', 'HEAD'); git('tag', 'tenant-violation')
    tenant_evidence, _ = execute(tenant_unsafe, 'tenant-leak', 'passed', 'failed')
    verify(tenant_unsafe, 'tenant-leak', tenant_evidence, 'unsatisfied')
    write(repo / tenant_source, tenant_good)
    git('add', SOURCE, tenant_source); git('commit', '-qm', 'Restore payment identity and tenant authorization')
    fixed = git('rev-parse', 'HEAD'); git('tag', 'fixed')
    evidence, project = execute(fixed, 'fixed', 'passed')
    fixed_report = verify(fixed, 'fixed', evidence, 'satisfied')
    for label, tests in [('missing', []), ('skipped', [dict(name=TEST, status='skipped')]),
                         ('infrastructure-error', [dict(name=TEST, status='error')]),
                         ('unrelated-green', [dict(name='shop.OtherTest.green', status='passed')]),
                         ('conflicting', [dict(name=TEST, status='passed'), dict(name=TEST, status='failed')])]:
        fixture = bundle(fixed, label, tests)
        verify(fixed, label, fixture, 'incomplete')
    stale = runs['baseline'][0]
    verify(fixed, 'wrong-head', (stale['bundle'], stale['receipt']), 'incomplete')
    cli('contracts', 'preview', *common, '--head', fixed, '--output', output / 'preview.json')
    write(repo / SOURCE, (repo / SOURCE).read_text() + '// local edit\n')
    cli('contracts', 'freshness', *common, '--head', fixed, '--preview', output / 'preview.json', expected=3)
    write(repo / SOURCE, git('show', 'fixed:' + SOURCE) + '\n')
    release_inputs = [*common, '--head', fixed, '--ci-bundle', evidence[0], '--ci-receipt', evidence[1]]
    artifact = project / 'target/shop.jar'
    cli('contracts', 'attest', *release_inputs, '--artifact', artifact, '--output', output / 'release')
    attestation = json.loads((output / 'release/attestation.json').read_text())
    assert attestation['state'] == 'issued'
    write(output / 'substituted.jar', 'different bytes than the CI-tested artifact\n')
    cli('contracts', 'attest', *release_inputs, '--artifact', output / 'substituted.jar', '--output', output / 'artifact-drift', expected=3)
    observations.append(dict(case='artifact-drift', result='held', synthetic_external_receipt=True))
    now = datetime.now(timezone.utc)
    def when(seconds): return (now - timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')
    telemetry = dict(environment='showcase', contract_id='PAYMENT-RETRY', deployment_id='synthetic-local-deployment',
                     head_sha=fixed, artifact_sha256=attestation['artifact_sha256'], started_at=when(60), ended_at=when(10), events=20, violations=0)
    for label, expected in [('healthy', 'accepted'), ('quiet', 'held'), ('violating', 'halt_required'), ('contained', 'contained'), ('reconciled', 'recovered')]:
        signal = dict(telemetry)
        if label == 'quiet': signal['events'] = 0
        if label in {'violating', 'contained', 'reconciled'}:
            signal.update(violations=1, first_violation_at=when(50))
        if label in {'contained', 'reconciled'}: signal['halt_confirmed_at'] = when(45)
        if label == 'reconciled': signal.update(reconciled_at=when(20), reconciliation_evidence='https://example.test/showcase/reconciliation')
        path = output / 'monitoring' / (label + '.json')
        receipt = output / 'monitoring' / (label + '-SIMULATED-receipt.json')
        write(path, signal)
        canonical_attestation = json.dumps(attestation, separators=(',', ':'), ensure_ascii=False).encode()
        write(receipt, dict(verified_source='delivery_monitor_export', observation_sha256=digest(path.read_bytes()), attestation_sha256=digest(canonical_attestation)))
        cli('contracts', 'rollout', *release_inputs, '--artifact', artifact, '--observations', path, '--monitor-receipt', receipt,
            '--output', output / 'rollout' / label, expected=0 if expected in {'accepted', 'recovered'} else 3)
        actual = json.loads((output / 'rollout' / label / 'rollout.json').read_text())['decision']
        assert actual == expected, (label, actual)
        observations.append(dict(case='rollout-' + label, result=actual, synthetic_external_receipt=True))
    study = dict(schema_version=1, dataset_kind='synthetic', tolerances=dict(minimum_changes=2, minimum_benign=1, minimum_violations=1, maximum_false_holds=0, maximum_escaped=0),
                 cases=[dict(contract_id='PAYMENT-RETRY', verification=report, reviews=[dict(reviewer='synthetic-owner-1', verdict=verdict), dict(reviewer='synthetic-owner-2', verdict=verdict)])
                        for report, verdict in [(fixed_report, 'benign'), (unsafe_report, 'violation')]])
    write(output / 'pilot-input.json', study)
    cli('contracts', 'pilot', '--study', output / 'pilot-input.json', '--output', output / 'pilot.json')
    mcp_requests = '\n'.join(json.dumps(value) for value in [dict(jsonrpc='2.0', id=1, method='initialize', params=dict(protocolVersion='2025-06-18')),
                    dict(jsonrpc='2.0', id=2, method='tools/call', params=dict(name='contracts_read', arguments={})),
                    dict(jsonrpc='2.0', id=3, method='tools/call', params=dict(name='contracts_verify', arguments={}))]) + '\n'
    mcp = subprocess.run(['java', '-jar', str(ROOT / 'target/undertow.jar'), 'contracts', 'mcp', *map(str, release_inputs)],
                         input=mcp_requests, capture_output=True, text=True, env=environment, timeout=60, check=True)
    responses = [json.loads(line) for line in mcp.stdout.splitlines()]
    assert responses[-1]['result']['structuredContent']['verification'] == 'satisfied'
    write(output / 'mcp-transcript.json', responses)
    write(output / 'summary.json', dict(schema_version=1, synthetic=True, real_junit_executions=6,
                external_provenance='SIMULATED: not real GitHub, independent human, or production acceptance', cases=observations,
                reproduction='reproduced_regression', artifact_attestation='issued', preview_after_mutation='stale', mcp='passed'))
    gallery = json.loads((output / 'summary.json').read_text())
    template = (ROOT / 'src/main/resources/contracts/showcase.html').read_text()
    payload = json.dumps(gallery, ensure_ascii=False).replace('<', '\\u003c')
    write(output / 'index.html', template.replace('__SHOWCASE_DATA__', payload))
    write(output / 'README.md', '# Synthetic assurance showcase\n\nReal JUnit execution; all CI and monitoring receipts and owner labels are simulated. No production or live AI acceptance. Start with summary.json, then results/, reproduction.json, release/, rollout/, and mcp-transcript.json.\n')
    print('Showcase passed: real assertions, exact evidence rejection, preview freshness, MCP, release/artifact binding, and controlled recovery. External receipts are SIMULATED.')


if __name__ == '__main__':
    main()
