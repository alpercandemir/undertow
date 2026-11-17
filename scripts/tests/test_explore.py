"""Public explore adoption boundaries and local report rendering."""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'experiments/behavior-search'))
import explore
import retry_behavior
import search
sys.path.pop(0)


class ExploreAdoptionTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.run = self.root / 'run'
        self.run.mkdir()
        self.subject = self.root / 'component'
        main = self.subject / retry_behavior.MAIN
        main.parent.mkdir(parents=True)
        fixture = ROOT / 'demo/refund-extraction'
        main.write_bytes((fixture / retry_behavior.MAIN).read_bytes())
        (self.subject / 'pom.xml').write_bytes((fixture / 'pom.xml').read_bytes())
        self.case = dict(initial=2, operations=[dict(action='refund', key=1, amount=1),
                                               dict(action='refund', key=1, amount=1)])
        trace = [dict(refunded=0, effects=0), dict(status='accepted', refunded=1, effects=1),
                 dict(status='accepted', refunded=2, effects=1)]
        violation = retry_behavior.evaluate(self.case, trace)
        result = dict(scenario=self.case, trace=trace, violation=violation)
        self.report = dict(outcome='counterexample_found', finding=dict(original=result, reduced=result),
                           oracle=search.oracle_identity(retry_behavior),
                           adapter=search.identity(search.adapter_paths(retry_behavior)),
                           subject=search.identity({str(path): self.subject / path for path in search.subject_paths(retry_behavior)}),
                           assumptions=retry_behavior.DEFINITION['scope'], counts={'search_completed': 1},
                           problems=[], seed=17, termination='first_violation')
        search.write_json(self.run / 'report.json', self.report)
        search.export_replay(self.run, self.case, violation['expectation'], self.report, behavior=retry_behavior)
        explore.draft_metadata(self.run, 'refund-retry', retry_behavior, self.report)

    def adopt(self, output='adoption'):
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            return explore.main(['--adopt', str(self.run), '--subject', str(self.subject),
                                 '--output', str(self.root / output)])

    def test_explicit_adoption_copies_one_draft_and_leaves_policy_unchanged(self):
        policy = self.subject / '.undertow/contracts.yaml'
        policy.parent.mkdir()
        policy.write_text('existing owner policy\n')
        self.assertEqual(0, self.adopt())
        target = self.subject / retry_behavior.TEST_FILE
        self.assertTrue(target.is_file())
        self.assertEqual(search.digest(self.run / target.name), search.digest(target))
        self.assertEqual('existing owner policy\n', policy.read_text())
        receipt = json.loads((self.root / 'adoption/adoption.json').read_text())
        self.assertFalse(receipt['policy_changed'])
        self.assertEqual('not_recorded', receipt['owner_approval'])

    def test_existing_test_is_never_overwritten(self):
        target = self.subject / retry_behavior.TEST_FILE
        target.parent.mkdir(parents=True)
        target.write_text('owner test\n')
        self.assertEqual(2, self.adopt())
        self.assertEqual('owner test\n', target.read_text())

    def test_modified_java_payload_cannot_be_adopted_even_with_an_updated_hash(self):
        source = self.run / retry_behavior.TEST_FILE.name
        source.write_text(source.read_text() + '\n// changed executable input\n')
        proposal = json.loads((self.run / 'contract-draft.json').read_text())
        proposal['test_sha256'] = search.digest(source)
        search.write_json(self.run / 'contract-draft.json', proposal)
        self.assertEqual(2, self.adopt())
        self.assertFalse((self.subject / retry_behavior.TEST_FILE).exists())

    def test_symlink_cannot_redirect_adopted_source_outside_the_component(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (self.subject / 'src/test').symlink_to(outside, target_is_directory=True)
        self.assertEqual(2, self.adopt())
        self.assertEqual([], list(outside.iterdir()))

    def test_changed_oracle_refuses_adoption(self):
        bundle = json.loads((self.run / 'replay.json').read_text())
        bundle['oracle']['sha256'] = '0' * 64
        search.write_json(self.run / 'replay.json', bundle)
        self.assertEqual(2, self.adopt())

    def test_a_nonfinding_cannot_supply_an_adoption_draft(self):
        self.report.update(outcome='no_counterexample_within_budget', finding=None)
        search.write_json(self.run / 'report.json', self.report)
        self.assertEqual(2, self.adopt())

    def test_report_escapes_execution_diagnostics_and_explains_actual_states(self):
        self.report['problems'] = ['<script>unexpected execution output</script>']
        (self.run / 'execution.md').write_text('raw observations\n')
        explore.render(self.run, 'refund-retry', retry_behavior, self.report, dict(problems=[]))
        html = (self.run / 'index.html').read_text()
        markdown = (self.run / 'report.md').read_text()
        self.assertNotIn('<script>', html)
        self.assertIn('&lt;script&gt;', html)
        self.assertIn('refunded 1 minor units', markdown)
        self.assertIn('refunded 2 minor units', markdown)
        self.assertIn('verified original finding is retained', markdown)

    def test_usage_does_not_need_a_runtime_or_create_artifacts(self):
        with redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as result:
            explore.main(['--help'])
        self.assertEqual(0, result.exception.code)


if __name__ == '__main__':
    unittest.main()
