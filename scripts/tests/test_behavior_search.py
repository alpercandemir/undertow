"""Behavior-search oracle, deterministic generation, reduction and Java boundary tests."""

import os
from pathlib import Path
import shutil
import sys
import subprocess
import tempfile
import time
import unittest

EXPERIMENT = Path(__file__).resolve().parents[2] / 'experiments/behavior-search'
sys.path.insert(0, str(EXPERIMENT))
import oracle
import search
import retry_behavior
import sequence_strategy
sys.path.pop(0)


class OracleAndSearchTest(unittest.TestCase):
    def test_declared_strategy_is_repeatable_and_preserves_domain_when_reducing(self):
        for seed in [17, 29, 101]:
            left, right = search.random.Random(seed), search.random.Random(seed)
            for _ in range(30):
                case = retry_behavior.generate(left, 12)
                self.assertEqual(case, retry_behavior.generate(right, 12))
                retry_behavior.validate(case)
                for candidate in retry_behavior.candidates(case):
                    retry_behavior.validate(candidate)

    def test_retry_at_exhausted_capture_is_accepted_without_another_effect(self):
        operation = dict(action='refund', key=1, amount=2)
        case = dict(initial=2, operations=[operation, operation.copy()])
        settled = dict(status='accepted', refunded=2, effects=1)
        self.assertIsNone(retry_behavior.evaluate(case, [dict(refunded=0, effects=0), settled, settled.copy()]))
        violation = retry_behavior.evaluate(case, [dict(refunded=0, effects=0), settled,
                                                   dict(status='rejected', refunded=2, effects=1)])
        self.assertEqual('retry_idempotency', violation['expectation'])

    def test_conflicting_retry_cannot_change_the_amount(self):
        case = dict(initial=10, operations=[dict(action='refund', key=1, amount=2),
                                            dict(action='refund', key=1, amount=1)])
        trace = [dict(refunded=0, effects=0), dict(status='accepted', refunded=2, effects=1),
                 dict(status='accepted', refunded=2, effects=1)]
        self.assertEqual('operation_key_amount_immutable', retry_behavior.evaluate(case, trace)['expectation'])

    def test_rejected_key_does_not_become_a_settled_operation(self):
        case = dict(initial=2, operations=[dict(action='refund', key=1, amount=0),
                                           dict(action='refund', key=1, amount=1)])
        trace = [dict(refunded=0, effects=0), dict(status='rejected', refunded=0, effects=0),
                 dict(status='accepted', refunded=1, effects=1)]
        self.assertIsNone(retry_behavior.evaluate(case, trace))

    def test_boundary_generation_is_repeatable_and_valid(self):
        for seed in [17, 29, 101]:
            left, right = search.random.Random(seed), search.random.Random(seed)
            for _ in range(100):
                case = search.generate(left, 12)
                self.assertEqual(case, search.generate(right, 12))
                oracle.validate(case)

    def test_reject_everything_is_not_a_vacuous_success(self):
        case = dict(capture=10, amounts=[3])
        result = oracle.evaluate(case, [dict(refunded=0, effects=0),
                                       dict(status='rejected', refunded=0, effects=0)])
        self.assertEqual('valid_refund_accepted', result['expectation'])

    def test_rejected_operation_cannot_change_real_state(self):
        result = oracle.evaluate(dict(capture=10, amounts=[0]),
                                 [dict(refunded=0, effects=0),
                                  dict(status='rejected', refunded=1, effects=0)])
        self.assertEqual('rejected_operation_leaves_state_unchanged', result['expectation'])

    def test_accepted_operation_must_have_an_effect(self):
        result = oracle.evaluate(dict(capture=10, amounts=[2]),
                                 [dict(refunded=0, effects=0),
                                  dict(status='accepted', refunded=0, effects=0)])
        self.assertEqual('accepted_refund_has_exact_effect', result['expectation'])

    def test_cap_is_first_violation_even_when_invalid_operation_is_accepted(self):
        result = oracle.evaluate(dict(capture=2, amounts=[2, 1]),
                                 [dict(refunded=0, effects=0),
                                  dict(status='accepted', refunded=2, effects=1),
                                  dict(status='accepted', refunded=3, effects=2)])
        self.assertEqual(oracle.CAP, result['expectation'])
        self.assertEqual(2, result['step'])

    def test_infrastructure_errors_cannot_become_violations(self):
        for response in ['execution_error\tjava.lang.IllegalStateException\n',
                         'ok\t0,0\n', 'ok\t0,0\tunknown,1,1\n', 'noise\n']:
            with self.assertRaises((search.ExecutionProblem, ValueError)):
                search.Runner.parse(response, 1)

    def test_reduction_preserves_same_obligation_and_preconditions(self):
        def execute(case):
            oracle.validate(case)
            total = 0
            trace = [dict(refunded=0, effects=0)]
            effects = 0
            for amount in case['amounts']:
                status = 'rejected'
                if 0 < amount <= case['capture']:
                    status = 'accepted'
                    total += amount
                    effects += 1
                trace.append(dict(status=status, refunded=total, effects=effects))
            return dict(violation=oracle.evaluate(case, trace))
        case = dict(capture=100, amounts=[0, 99, 2, -1])
        reduced, metadata = search.reduce_case(case, oracle.CAP, execute, 200, 5)
        self.assertTrue(metadata['completed'])
        self.assertEqual(2, reduced['capture'])
        self.assertEqual(2, len(reduced['amounts']))
        self.assertGreater(sum(reduced['amounts']), reduced['capture'])
        self.assertEqual(oracle.CAP, execute(reduced)['violation']['expectation'])
        unchanged, metadata = search.reduce_case(case, oracle.CAP, execute, 0, 5)
        self.assertEqual(case, unchanged)
        self.assertFalse(metadata['completed'])

    def test_export_uses_expectation_not_observed_buggy_values(self):
        test = oracle.junit(dict(capture=2, amounts=[1, 2]))
        self.assertIn('service.refunded() <= capture', test)
        self.assertIn('assertEquals(valid, accepted', test)
        self.assertNotIn('assertEquals(3', test)


class JavaBoundaryTest(unittest.TestCase):
    """Compile real Java fixtures once; never replace Java execution with a model."""

    @classmethod
    def setUpClass(cls):
        javac = shutil.which('javac')
        java = shutil.which('java')
        if not java or not javac:
            raise RuntimeError('Java 21+ required for behavior-search boundary tests')
        cls.directory = tempfile.TemporaryDirectory()
        cls.root = Path(cls.directory.name)
        settings = subprocess.check_output([java, '-XshowSettings:properties', '-version'],
                                           stderr=subprocess.STDOUT, text=True, timeout=10)
        home = next(line.split('=', 1)[1].strip() for line in settings.splitlines()
                    if line.strip().startswith('java.home ='))
        cls.env = search.environment(Path(home))
        cls.counts = search.Counter()
        fixture = (EXPERIMENT.parents[1] / 'demo/refund-extraction' / search.MAIN).read_text()
        guard = 'if (amount <= 0 || amount > captured - refunded)'
        for name, content in {
                'correct': fixture,
                'defect': fixture.replace(guard, 'if (amount <= 0 || amount > captured)'),
                'crash': fixture.replace(guard, 'if (true) throw new IllegalStateException("crash");\n        ' + guard),
                'reject': fixture.replace(guard, 'if (true) throw new IllegalArgumentException("all");\n        ' + guard),
                'hang': fixture.replace(guard, 'while (amount > 0) { Thread.onSpinWait(); }\n        ' + guard),
        }.items():
            project = cls.root / name
            (project / search.MAIN.parent).mkdir(parents=True)
            source = project / search.MAIN
            source.write_text(content)
            classes = project / 'target/classes'
            classes.mkdir(parents=True)
            code = search.command([javac, '--release', '21', '-d', str(classes), str(source),
                                   *map(str, search.adapter_paths().values())],
                                  cls.env, 30, project / 'compile.log')
            if code:
                raise RuntimeError((project / 'compile.log').read_text())

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def runner(self, name, timeout=2):
        runner = search.Runner(self.root / name, self.env, timeout,
                               time.monotonic() + 10, search.Counter())
        self.addCleanup(runner.close)
        return runner

    def test_real_state_is_isolated_and_cap_reachable(self):
        runner = self.runner('correct')
        for _ in range(2):
            for case in oracle.non_vacuity_cases():
                self.assertIsNone(runner.execute(case, 'test')['violation'])

    def test_generated_search_finds_real_java_cap_defect(self):
        runner = self.runner('defect')
        rng = search.random.Random(17)
        for _ in range(100):
            result = runner.execute(search.generate(rng, 12), 'test')
            if result['violation']:
                self.assertEqual(oracle.CAP, result['violation']['expectation'])
                self.assertEqual(result, runner.execute(result['scenario'], 'replay'))
                return
        self.fail('Known defect not found within deterministic budget')

    def test_unexpected_java_exception_is_an_execution_problem(self):
        with self.assertRaisesRegex(search.ExecutionProblem, 'IllegalStateException'):
            self.runner('crash').execute(dict(capture=2, amounts=[1]), 'test')

    def test_timeout_is_an_execution_problem(self):
        with self.assertRaisesRegex(search.ExecutionProblem, 'timeout'):
            self.runner('hang', timeout=0.5).execute(dict(capture=2, amounts=[1]), 'test')

    def test_rejecting_everything_fails_non_vacuity(self):
        result = self.runner('reject').execute(oracle.non_vacuity_cases()[0], 'test')
        self.assertEqual('valid_refund_accepted', result['violation']['expectation'])

    def run_snapshot(self, content, name):
        subject = self.root / ('input-' + name)
        (subject / search.MAIN.parent).mkdir(parents=True)
        (subject / search.MAIN).write_text(content)
        fixture = EXPERIMENT.parents[1] / 'demo/refund-extraction'
        (subject / 'pom.xml').write_bytes((fixture / 'pom.xml').read_bytes())
        output = self.root / ('run-' + name)
        report = search.run(subject, output, Path(self.env['JAVA_HOME']))
        return report, output

    def test_build_failure_cannot_reuse_stale_subject_classes(self):
        subject = self.root / 'input-uncompilable'
        (subject / search.MAIN.parent).mkdir(parents=True)
        (subject / search.MAIN).write_text('this does not compile')
        fixture = EXPERIMENT.parents[1] / 'demo/refund-extraction'
        (subject / 'pom.xml').write_bytes((fixture / 'pom.xml').read_bytes())
        shutil.copytree(self.root / 'correct/target', subject / 'target')
        output = self.root / 'run-uncompilable'
        report = search.run(subject, output, Path(self.env['JAVA_HOME']))
        self.assertEqual('incomplete', report['outcome'])
        self.assertIsNone(report['finding'])
        self.assertEqual(0, report['counts']['search_attempted'])
        self.assertIn('build failed', report['problems'][0])
        self.assertFalse((output / 'subject/target/classes/example/refunds/RefundService.class').exists())

    def test_crashed_subject_report_is_incomplete(self):
        report, _ = self.run_snapshot((self.root / 'crash' / search.MAIN).read_text(), 'crashed-report')
        self.assertEqual('incomplete', report['outcome'])
        self.assertIsNone(report['finding'])
        self.assertIn('IllegalStateException', report['problems'][0])

    def test_verified_finding_survives_later_reduction_execution_problem(self):
        code = (self.root / 'defect' / search.MAIN).read_text().replace(
            'if (captured <= 0)',
            'if (captured == 2) throw new IllegalStateException("reduction failure");\n        if (captured <= 0)')
        report, output = self.run_snapshot(code, 'reduction-problem')
        self.assertEqual('counterexample_found', report['outcome'])
        self.assertTrue(report['unfinished_work'])
        self.assertIn('IllegalStateException', report['problems'][0])
        self.assertEqual(report['finding']['original'], report['finding']['original_replay'])
        self.assertTrue((output / 'replay.json').exists())
        self.assertTrue((output / 'GeneratedRefundBehaviorTest.java').exists())


if __name__ == '__main__':
    unittest.main()
