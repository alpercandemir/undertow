import io
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from test_workflow_helpers import load_script

trigger = load_script('trigger-policy.py')
formats = load_script('ci-formats.py')
importer = load_script('import-ci.py')
feedback = load_script('feedback.py')
evaluation = load_script('evaluate.py')
pilot = load_script('pilot-measure.py')
snapshots = load_script('github-snapshots.py')


class TriggerPolicyTest(unittest.TestCase):
    def event(self):
        return dict(action='opened', repository={'default_branch': 'main'},
                    pull_request=dict(draft=False, state="open", head={'repo': {'full_name': 'owner/repo'}}, base={'ref': 'main'}))

    def test_opt_in_internal_and_draft_rules(self):
        event = self.event()
        self.assertTrue(trigger.eligible(event, 'pull_request_target', 'owner/repo', 'true'))
        self.assertFalse(trigger.eligible(event, 'pull_request_target', 'owner/repo', 'false'))
        event['pull_request']['draft'] = True
        self.assertFalse(trigger.eligible(event, 'pull_request_target', 'owner/repo', 'true'))
        event['action'] = 'converted_to_draft'
        self.assertFalse(trigger.eligible(event, 'pull_request_target', 'owner/repo', 'true'))
        event['action'] = 'opened'
        event['pull_request']['draft'] = False
        event['pull_request']['head']['repo']['full_name'] = 'external/repo'
        self.assertFalse(trigger.eligible(event, 'pull_request_target', 'owner/repo', 'true'))
        self.assertTrue(trigger.eligible({'inputs': {'pr_number': '7'}}, 'workflow_dispatch', 'owner/repo', 'false'))
        self.assertFalse(trigger.eligible({'inputs': {'pr_number': '7; malicious'}}, 'workflow_dispatch', 'owner/repo', 'false'))

    def test_fetch_metadata_rejects_hosts_and_paths(self):
        for repository in ['https://evil.test/x', '../repo', 'owner/../../repo', 'owner/repo?token=secret']:
            with self.assertRaises(ValueError):
                snapshots.validate(dict(repository=repository, base_sha='a'*40, head_sha='b'*40))


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


class MeasurementAndFeedbackTest(unittest.TestCase):
    def test_unknown_prices_and_failed_runs_remain_visible(self):
        row = evaluation.score_run(SimpleNamespace(input_rate=None, output_rate=None),
                dict(id='case', split='heldout', unsafe=True, rule_id='rule', severity='HIGH'),
                dict(status='failed', findings=[], elapsed_millis=7, model_calls=1, tool_calls=0, input_tokens=0, output_tokens=0), 'tools', 0)
        self.assertEqual(row['false_negative'], 1)
        summary = evaluation.summarize([row])
        self.assertIsNone(summary['cost_usd'])
        self.assertEqual(summary['completion_rate'], 0)
        self.assertIsNone(summary['high_risk_precision']['value'])

    def test_feedback_is_append_only_and_requires_report_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'feedback.jsonl'
            report = dict(run_id='run', findings=[{'id': 'f'}])
            feedback.append_feedback(path, report, 'f', 'alice', 'correct', 'fixed', 'Observed risk')
            feedback.append_feedback(path, report, 'f', 'bob', 'incorrect', 'false_positive', 'Counterexample')
            self.assertEqual(len(path.read_text().splitlines()), 2)
            with self.assertRaises(ValueError):
                feedback.append_feedback(path, report, 'unknown', 'alice', 'correct', 'fixed', 'Invalid')
            self.assertEqual(len(path.read_text().splitlines()), 2)

    def test_independent_reviewers_and_adjudication_are_required(self):
        entries = [dict(run_id='run', finding_id='f', reviewer='alice', assessment='correct')]
        self.assertIsNone(pilot.human_decision(entries, {}, ('run', 'f')))
        entries.append(dict(run_id='run', finding_id='f', reviewer='bob', assessment='incorrect'))
        self.assertIsNone(pilot.human_decision(entries, {}, ('run', 'f')))
        self.assertEqual(pilot.human_decision(entries, {'run/f': dict(reviewer='lead', rationale='Evidence checked', assessment='correct')}, ('run', 'f')), 'correct')
        result = pilot.measure(dict(schema_version=1, dataset_kind='authorized_historical_prs', cases=[]), Path('.'), [])
        self.assertFalse(result['quality_targets_met'])
        self.assertFalse(result['sample_gate_met'])
