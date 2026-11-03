#!/usr/bin/env python3
"""Exercise approved external-oracle execution against a small owned fixture in Docker.

The image and dependency cache must be prepared separately. This performs no image
pull, model call, GitHub import, publication, or production operation.
"""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('showcase', ROOT / 'scripts/assurance-showcase.py')
SHOWCASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SHOWCASE)
SPEC2 = importlib.util.spec_from_file_location('ci_formats', ROOT / 'scripts/ci-formats.py')
FORMATS = importlib.util.module_from_spec(SPEC2)
SPEC2.loader.exec_module(FORMATS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--dependency-cache', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    repo = output / 'repo'
    repo.mkdir()
    SHOWCASE.write(repo / 'pom.xml', SHOWCASE.POM)
    SHOWCASE.write(repo / SHOWCASE.SOURCE, 'package shop; public final class Payment { public static String retryKey(String key) { return key; } }\n')
    oracle_path = 'src/test/java/shop/PaymentTest.java'
    oracle = br'''package shop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PaymentTest {
 @Test void retryPreservesIdentity() { assertEquals("operation-42", Payment.retryKey("operation-42")); }
 @Test void isolationSurfaceIsRestricted() throws Exception {
   assertNull(System.getenv("GEMINI_API_KEY")); assertNull(System.getenv("GITHUB_TOKEN"));
   assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock")));
   String status = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/self/status"));
   assertTrue(status.matches("(?s).*Uid:\\s+65534\\s+65534.*"));
   assertThrows(java.io.IOException.class, () -> java.nio.file.Files.writeString(java.nio.file.Path.of("/etc/undertow-write-test"), "denied"));
   String routes = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/net/route"));
   assertEquals(1, routes.lines().count());
 }
}'''
    SHOWCASE.write(repo / oracle_path, oracle.decode())
    archive = output / 'oracle.tar'
    with tarfile.open(archive, 'w') as tar:
        entry = tarfile.TarInfo(oracle_path)
        entry.size = len(oracle)
        tar.addfile(entry, io.BytesIO(oracle))
    manifest = dict(schema_version=1, repository='example/isolation-showcase', oracle_paths=['pom.xml'],
                    regression_artifacts=[dict(id='approved-oracle', sha256=hashlib.sha256(archive.read_bytes()).hexdigest())],
                    contracts=[dict(id='PAYMENT-RETRY', version='1', owner='synthetic demo owner', expectation='Preserve retry identity under an isolated approved oracle',
                                    scope=[SHOWCASE.SOURCE], verifier='junit', tests=[dict(id='shop.PaymentTest.' + name, sources=[oracle_path], artifact='approved-oracle')
                                              for name in ['retryPreservesIdentity', 'isolationSurfaceIsRestricted']])])
    SHOWCASE.write(repo / '.undertow/contracts.yaml', manifest)
    def git(*arguments):
        return subprocess.check_output(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'user.name=Isolation fixture',
               '-c', 'user.email=fixture@undertow.invalid', '-C', str(repo), *arguments], text=True).strip()
    git('init', '-q'); git('add', '.'); git('commit', '-qm', 'Approved isolated regression fixture')
    sha = git('rev-parse', 'HEAD')
    with (output / 'subject.tar').open('wb') as stream:
        subprocess.run(['git', '--no-replace-objects', '-C', str(repo), 'archive', '--format=tar', sha], stdout=stream, check=True)
    subprocess.run(['java', '-jar', str(ROOT / 'target/undertow.jar'), 'contracts', 'regression-plan', '--repo', str(repo),
                    '--base', sha, '--head', sha, '--oracle-artifact', 'approved-oracle', '--image', args.image,
                    '--output', str(output / 'plan.json')], check=True, timeout=60)
    result = subprocess.run(['python3', str(ROOT / 'scripts/run-approved-regression.py'), '--plan', str(output / 'plan.json'),
                    '--subject-archive', str(output / 'subject.tar'), '--oracle-archive', str(archive),
                    '--dependency-cache', str(args.dependency_cache.resolve()), '--output', str(output / 'execution')], timeout=160)
    xml = output / 'execution/TEST-shop.PaymentTest.xml'
    cases = FORMATS.junit(xml.read_bytes()) if xml.is_file() else []
    expected = {'shop.PaymentTest.retryPreservesIdentity', 'shop.PaymentTest.isolationSurfaceIsRestricted'}
    passed = result.returncode == 0 and {row['name'] for row in cases} == expected and all(row['status']=='passed' for row in cases)
    SHOWCASE.write(output / 'isolation.json', dict(schema_version=1, accepted=passed, executions=cases,
                   image=args.image, source_sha=sha, real_container_execution=True, trusted_ci_import=False,
                   scope='Controlled local Linux container surface checks; not a global sandbox/security claim'))
    print('Isolated oracle showcase: ' + ('passed' if passed else 'incomplete/failed; inspect execution/executor.log'))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
