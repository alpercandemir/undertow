"""Trusted CI import provenance and format boundaries."""
import hashlib
import io
import tempfile
from pathlib import Path
from unittest.mock import patch
import json
import unittest
import zipfile
from script_support import load_script

formats = load_script("ci-formats.py")
importer = load_script("import-ci.py")


class CiImportTest(unittest.TestCase):
    def test_trusted_import_retains_contract_binding_in_bundle_and_receipt(self):
        archive_data = io.BytesIO()
        with zipfile.ZipFile(archive_data, 'w') as archive:
            archive.writestr('TEST-Payment.xml', '<testsuite tests="1" failures="1"><testcase classname="Payment" name="retry"><failure/></testcase></testsuite>')
        archive_bytes = archive_data.getvalue()
        artifact = dict(id=7, name='single-module-evidence', expired=False,
                        workflow_run=dict(id=1, head_sha='b'*40),
                        digest='sha256:' + hashlib.sha256(archive_bytes).hexdigest())
        run = dict(id=1, repository=dict(full_name='owner/repo'), workflow_id=42,
                   head_sha='b'*40, status='completed', conclusion='failure', event='push',
                   html_url='https://github.com/owner/repo/actions/runs/1')
        binding = dict(repository='owner/repo', policy_commit='a'*40,
                       manifest_path='.undertow/contracts.yaml', manifest_sha256='c'*64,
                       oracle_sha256='d'*64)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'policy.json').write_text(json.dumps(binding))
            arguments = ['import-ci.py', '--repository', 'owner/repo', '--run-id', '1',
                         '--workflow-id', '42', '--base', 'a'*40, '--head', 'b'*40,
                         '--output', str(root / 'bundle.json'), '--receipt', str(root / 'receipt.json'),
                         '--contract-policy', str(root / 'policy.json')]
            with patch('sys.argv', arguments), patch.object(importer, 'get', side_effect=[
                    json.dumps(run).encode(), json.dumps(dict(artifacts=[artifact])).encode(), archive_bytes]):
                importer.main()
            bundle = json.loads((root / 'bundle.json').read_text())
            receipt = json.loads((root / 'receipt.json').read_text())
            self.assertEqual(bundle['contract_binding'], binding)
            self.assertEqual(receipt['contract_binding'], binding)
            self.assertEqual(receipt['run_conclusion'], 'failure')
            self.assertEqual(bundle['artifacts'][0]['content'][0]['status'], 'failed')
            self.assertEqual(receipt['bundle_sha256'], hashlib.sha256((root / 'bundle.json').read_bytes()).hexdigest())

    def test_malformed_suite_counts_and_conflicting_outcomes_are_rejected(self):
        for xml in (b'<testsuite tests="1" failures="1"><testcase name="green"/></testsuite>',
                    b'<testsuite><testcase name="conflict"><failure/><skipped/></testcase></testsuite>',
                    b'<testsuite><testcase name="flaky"><flakyFailure/></testcase></testsuite>',
                    b'<testsuite><testcase name="green"/><error/></testsuite>',
                    b'<testsuite tests="-1"/>'):
            with self.assertRaises(ValueError):
                formats.junit(xml)

    def test_contract_binding_requires_exact_repository_and_pinned_digests(self):
        binding = dict(repository='owner/repo', policy_commit='a'*40,
                       manifest_path='.undertow/contracts.yaml', manifest_sha256='b'*64,
                       oracle_sha256='c'*64)
        importer.validate_contract_binding(binding, 'owner/repo')
        for changed in (dict(repository='other/repo'), dict(policy_commit='main'),
                        dict(oracle_sha256=''), dict(manifest_path='../outside'), dict(command='run')):
            with self.assertRaises(ValueError):
                importer.validate_contract_binding({**binding, **changed}, 'owner/repo')

    def test_test_failures_are_distinct_from_spotbugs(self):
        results = formats.junit(b'<testsuite><testcase name="passed"/><testcase name="failed"><failure/></testcase><testcase name="error"><error/></testcase><testcase name="skipped"><skipped/></testcase></testsuite>')
        self.assertEqual([item['status'] for item in results], ['passed', 'failed', 'error', 'skipped'])
        with self.assertRaises(ValueError):
            formats.junit(b'<testsuites><testsuite tests="2"/></testsuites>')
        bugs = formats.spotbugs(b'<BugCollection><BugInstance type="NP_NULL"><SourceLine sourcepath="pay/Order.java" start="7"/></BugInstance></BugCollection>')
        self.assertEqual(bugs[0]['path'], 'src/main/java/pay/Order.java')
        with self.assertRaises(ValueError):
            formats.junit(b'<!DOCTYPE doc [<!ENTITY secret SYSTEM "file:///secret">]><testsuite/>')

    def test_origin_and_source_digest_are_required(self):
        run = dict(id=1, repository={'full_name': 'owner/repo'}, workflow_id=42, head_sha='a'*40, status='completed', event='push')
        artifact = dict(workflow_run={'id': 1, 'head_sha': 'a'*40}, expired=False, digest='sha256:'+'b'*64)
        importer.verify_origin(run, artifact, 'owner/repo', '42', 'a'*40)
        for change in [{'head_sha': 'c'*40}, {'workflow_id': 99}, {'repository': {'full_name': 'other/repo'}}, {'event': 'pull_request'}]:
            with self.assertRaises(ValueError):
                importer.verify_origin({**run, **change}, artifact, 'owner/repo', '42', 'a'*40)

    def test_missing_analyzer_artifact_does_not_become_clean_result(self):
        content = io.BytesIO()
        with zipfile.ZipFile(content, 'w') as archive:
            archive.writestr('TEST-test.xml', '<testsuite><testcase name="a"/></testsuite>')
        self.assertEqual([kind for kind, _ in importer.normalize_archive(content.getvalue())], ['tests'])
        content = io.BytesIO()
        with zipfile.ZipFile(content, 'w') as archive:
            archive.writestr('../outside', 'bad')
        with self.assertRaises(ValueError):
            importer.normalize_archive(content.getvalue())
