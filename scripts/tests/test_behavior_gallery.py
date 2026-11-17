"""Prevent mislabeled gallery outcomes from being reported as verified examples."""

import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('behavior_gallery', ROOT / 'scripts/behavior-gallery.py')
gallery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gallery)


class GalleryTest(unittest.TestCase):
    def test_unrelated_execution_problem_cannot_satisfy_compiler_case(self):
        with self.assertRaises(gallery.search.ExecutionProblem):
            gallery.validate_build_problem('Could not resolve repository: connection timeout', {}, {})
        gallery.validate_build_problem('COMPILATION ERROR: cannot find symbol gallery_missing_symbol', {}, {})
        with self.assertRaises(gallery.search.ExecutionProblem):
            gallery.validate_build_problem('COMPILATION ERROR gallery_missing_symbol', {}, {'finding': {'expectation': 'retry'}})

    def test_wrong_rule_or_missing_replay_is_not_a_verified_violation(self):
        case = dict(expected='VIOLATION', expectation='retry_idempotency')
        decision = dict(exit_code=1, search_outcome='counterexample_found', replay='reproduced',
                        candidate_junit=dict(status='expectation_assertion_failed'), problems=[])
        report = dict(finding=dict(expectation='operation_key_amount_immutable'))
        with self.assertRaises(gallery.search.ExecutionProblem):
            gallery.validate_observation(case, decision, report)
        report['finding']['expectation'] = 'retry_idempotency'
        gallery.validate_observation(case, decision, report)
        decision['replay'] = 'not completed'
        with self.assertRaises(gallery.search.ExecutionProblem):
            gallery.validate_observation(case, decision, report)

    def test_happy_path_requires_complete_search_and_passing_existing_tests(self):
        case = dict(expected='PASS')
        decision = dict(exit_code=0, search_outcome='no_counterexample_within_budget', baseline=dict(status='passed'))
        report = dict(finding=None, counts=dict(search_completed=2), budgets=dict(scenarios=100))
        with self.assertRaises(gallery.search.ExecutionProblem):
            gallery.validate_observation(case, decision, report)
        report['counts']['search_completed'] = 100
        gallery.validate_observation(case, decision, report)
        decision['baseline']['status'] = 'assertion_failed'
        with self.assertRaises(gallery.search.ExecutionProblem):
            gallery.validate_observation(case, decision, report)

    def test_existing_test_failure_is_not_labeled_a_discovered_business_violation(self):
        self.assertEqual('EXISTING TEST FAILURE', gallery.observed_status(dict(
            exit_code=1, search_outcome='no_counterexample_within_budget')))

    def test_changed_fixture_cannot_silently_skip_mutation(self):
        with self.assertRaises(gallery.search.ExecutionProblem):
            gallery.mutation('different source', 'refund-over-cap')
        with self.assertRaises(gallery.search.ExecutionProblem):
            gallery.mutation('amount > captured - refunded\namount > captured - refunded', 'refund-over-cap')

    def test_partial_gallery_is_not_presented_as_complete(self):
        summary = gallery.render([])
        self.assertIn('0/8 cases matched', summary)
        self.assertIn('0/8 completed', summary)

    def test_correction_failure_never_renders_as_a_passing_correction(self):
        row = dict(id='refund-double-charge', title='Retry', rule='No extra effects', profile='refund-retry',
                   expected='VIOLATION', observed='VIOLATION', validation='failed', baseline='passed',
                   problems=['Correction suite failed'], finding=dict(
                       scenario=dict(initial=2, operations=[dict(action='refund', key=1, amount=1)]),
                       trace=[dict(refunded=0, effects=0), dict(status='accepted', refunded=2, effects=1)],
                       violation=dict(expectation='retry_idempotency', step=1,
                                      expected=dict(status='accepted', refunded=1, effects=1),
                                      observed=dict(status='accepted', refunded=2, effects=1))))
        summary = gallery.render([row])
        self.assertIn('Correction validation did not complete successfully', summary)
        self.assertNotIn('full correction suite passed', summary)


if __name__ == '__main__':
    unittest.main()
