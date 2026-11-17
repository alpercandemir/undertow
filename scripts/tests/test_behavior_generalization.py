"""Actual-Java checks for the second component and the frozen validation boundary."""

import os
from pathlib import Path
import random
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

EXPERIMENT = Path(__file__).resolve().parents[2] / 'experiments/behavior-search'
sys.path.insert(0, str(EXPERIMENT))
import search
import ticket_behavior
import run_generalization
sys.path.pop(0)


class FrozenStrategyTest(unittest.TestCase):
    def test_actual_freeze_is_intact(self):
        lock = run_generalization.verify_lock()
        self.assertEqual([211, 307, 401], lock['validation_seeds'])

    def test_changed_strategy_cannot_keep_the_original_freeze_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in [*run_generalization.LOCKED_FILES, 'strategy-lock.json']:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((EXPERIMENT / name).read_bytes())
            (root / 'sequence_strategy.py').write_text('changed strategy\n')
            with self.assertRaisesRegex(ValueError, 'Frozen strategy changed'):
                run_generalization.verify_lock(root)

    def test_second_grammar_uses_the_same_strategy_with_valid_reductions(self):
        for seed in [211, 307, 401]:
            left, right = random.Random(seed), random.Random(seed)
            for _ in range(25):
                case = ticket_behavior.generate(left, 12)
                self.assertEqual(case, ticket_behavior.generate(right, 12))
                ticket_behavior.validate(case)
                for reduced in ticket_behavior.candidates(case):
                    ticket_behavior.validate(reduced)

    def test_harmless_duplicate_return_difference_is_allowed(self):
        case = dict(initial=0, operations=[dict(action='accept', actor='recipient'),
                                           dict(action='accept', actor='recipient')])
        initial = dict(owner=0, accepted=0)
        for status in ['accepted', 'rejected']:
            trace = [initial, dict(status='accepted', owner=1, accepted=1),
                     dict(status=status, owner=1, accepted=1)]
            self.assertIsNone(ticket_behavior.evaluate(case, trace))


class TicketExecutionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.root = Path(cls.directory.name)
        java, javac = shutil.which('java'), shutil.which('javac')
        if not java or not javac:
            raise RuntimeError('Java 21+ required')
        settings = subprocess.check_output([java, '-XshowSettings:properties', '-version'],
                                           stderr=subprocess.STDOUT, text=True, timeout=10)
        home = next(line.split('=', 1)[1].strip() for line in settings.splitlines()
                    if line.strip().startswith('java.home ='))
        cls.env = search.environment(Path(home))
        cls.subjects = {}
        for name, behavior, code, _ in run_generalization.candidates():
            if name not in {'ticket-correct', 'ticket-preview-after-settlement', 'ticket-authorization-after-settlement'}:
                continue
            project = cls.root / name
            (project / behavior.MAIN.parent).mkdir(parents=True)
            source = project / behavior.MAIN
            source.write_text(code)
            classes = project / 'target/classes'
            classes.mkdir(parents=True)
            result = search.command([javac, '--release', '21', '-d', str(classes), str(source),
                                     *map(str, search.adapter_paths(behavior).values())], cls.env,
                                    30, project / 'compile.log')
            if result:
                raise RuntimeError((project / 'compile.log').read_text())
            cls.subjects[name] = project

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def runner(self, name):
        runner = search.Runner(self.subjects[name], self.env, 2, time.monotonic() + 15,
                               search.Counter(), ticket_behavior)
        self.addCleanup(runner.close)
        return runner

    def test_correct_preview_and_authorization_are_observed_in_real_state(self):
        runner = self.runner('ticket-correct')
        case = dict(initial=0, operations=[dict(action='preview'), dict(action='accept', actor='other'),
                                           dict(action='accept', actor='recipient'), dict(action='preview'),
                                           dict(action='accept', actor='sender')])
        first = runner.execute(case, 'test')
        self.assertIsNone(first['violation'])
        self.assertEqual(dict(status='rejected', owner=1, accepted=1), first['trace'][-1])
        self.assertEqual(first, runner.execute(case, 'reset'))

    def test_generated_preview_failure_reduces_to_a_valid_stateful_sequence(self):
        runner = self.runner('ticket-preview-after-settlement')
        rng = random.Random(211)
        for _ in range(100):
            result = runner.execute(ticket_behavior.generate(rng, 12), 'search')
            if result['violation']:
                self.assertEqual('previews_are_read_only', result['violation']['expectation'])
                reduced, metadata = search.reduce_case(result['scenario'], 'previews_are_read_only',
                    lambda case: runner.execute(case, 'reduce'), 200, 5, ticket_behavior.candidates)
                self.assertTrue(metadata['completed'])
                self.assertEqual([dict(action='accept', actor='recipient'), dict(action='preview')], reduced['operations'])
                self.assertEqual(2, runner.execute(reduced, 'replay')['violation']['step'])
                return
        self.fail('Frozen strategy missed the stateful preview defect')

    def test_unauthorized_actor_is_rejected_after_settlement_too(self):
        result = self.runner('ticket-authorization-after-settlement').execute(
            dict(initial=0, operations=[dict(action='accept', actor='recipient'),
                                       dict(action='accept', actor='other')]), 'test')
        self.assertEqual('only_recipient_accepts', result['violation']['expectation'])
        self.assertEqual(2, result['trace'][-1]['owner'])


if __name__ == '__main__':
    unittest.main()
