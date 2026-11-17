"""Decision boundaries: real assertions, incomplete evidence and honest CI status."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('behavior_review', ROOT / 'scripts/behavior-review.py')
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


class BehaviorReviewTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.reports = self.root / 'target/surefire-reports'
        self.reports.mkdir(parents=True)

    def xml(self, cases):
        (self.reports / 'TEST-example.xml').write_text('<testsuite>' + cases + '</testsuite>')

    def test_suite_failures_are_not_execution_errors(self):
        self.xml('<testcase classname="Example" name="rule"><failure/></testcase>')
        self.assertEqual('assertion_failed', review.suite_result(self.root, 1)['status'])
        with self.assertRaises(review.search.ExecutionProblem):
            review.suite_result(self.root, 0)
        self.xml('<testcase classname="Example" name="rule"><error/></testcase>')
        with self.assertRaises(review.search.ExecutionProblem):
            review.suite_result(self.root, 1)

    def test_missing_or_skipped_adopted_test_is_not_success(self):
        self.xml('<testcase classname="Example" name="other"/>')
        with self.assertRaises(review.search.ExecutionProblem):
            review.suite_result(self.root, 0, 'Example.generated')
        self.xml('<testcase classname="Example" name="other"/>'
                 '<testcase classname="Example" name="generated"><skipped/></testcase>')
        with self.assertRaises(review.search.ExecutionProblem):
            review.suite_result(self.root, 0, 'Example.generated')
        self.xml('<testcase classname="Example" name="generated"/>')
        self.assertEqual('passed', review.suite_result(self.root, 0, 'Example.generated')['status'])

    def test_no_xml_is_incomplete_even_when_maven_returns_zero(self):
        with self.assertRaises(review.search.ExecutionProblem):
            review.suite_result(self.root, 0)

    def test_real_finding_is_red_but_labeled_calibration_is_green(self):
        result = dict(mode='candidate review', problems=[], search_outcome='counterexample_found',
                      baseline=dict(status='passed'))
        self.assertEqual(1, review.conclusion(result)[1])
        result['mode'] = 'authored calibration'
        self.assertEqual(0, review.conclusion(result)[1])
        result['problems'] = ['replay failed']
        self.assertEqual(2, review.conclusion(result)[1])

    def test_clear_search_does_not_hide_existing_test_failure(self):
        result = dict(mode='candidate review', problems=[], search_outcome='no_counterexample_within_budget',
                      baseline=dict(status='assertion_failed'))
        self.assertEqual(1, review.conclusion(result)[1])

    def test_partial_finding_survives_later_execution_failure(self):
        def fail(args, result):
            result.update(search_outcome='counterexample_found', baseline=dict(status='passed'))
            raise review.search.ExecutionProblem('generated JUnit crashed')
        output = self.root / 'run'
        with patch.object(review, 'run', side_effect=fail):
            code = review.main(['--example', 'refund-retry', '--java-home', str(self.root), '--output', str(output)])
        result = json.loads((output / 'decision.json').read_text())
        self.assertEqual(2, code)
        self.assertEqual('counterexample_found', result['search_outcome'])
        self.assertIn('INCOMPLETE', (output / 'summary.md').read_text())
        self.assertNotIn('CALIBRATION PASSED', (output / 'summary.md').read_text())
        self.assertNotIn('Added coverage observed', (output / 'summary.md').read_text())

    def test_output_reuse_never_overwrites_prior_evidence(self):
        output = self.root / 'run'
        output.mkdir()
        sentinel = output / 'decision.json'
        sentinel.write_text('original evidence')
        with self.assertRaises(FileExistsError):
            review.main(['--example', 'refund-retry', '--java-home', str(self.root), '--output', str(output)])
        self.assertEqual('original evidence', sentinel.read_text())


if __name__ == '__main__':
    unittest.main()
