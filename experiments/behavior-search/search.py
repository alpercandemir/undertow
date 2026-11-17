#!/usr/bin/env python3
"""Experimental bounded search for the explicitly selected owned refund fixture."""

import argparse
from collections import Counter
import hashlib
import importlib
import json
import os
from pathlib import Path
import platform
import random
import selectors
import shutil
import signal
import subprocess
import time
import xml.etree.ElementTree as ET

import oracle

HERE = Path(__file__).resolve().parent
MAIN = Path('src/main/java/example/refunds/RefundService.java')
GENERATOR_VERSION = 'refund-boundaries-v1'
MAX_OUTPUT = 1024 * 1024
MAX_RESPONSE = 4096


def subject_paths(behavior=oracle):
    return [getattr(behavior, 'MAIN', MAIN), Path('pom.xml')]


def adapter_paths(behavior=oracle):
    names = getattr(behavior, 'JAVA_FILES',
                    ('ExecutionAdapter.java', 'ScenarioProtocol.java', 'RefundAdapter.java', 'ScenarioRunner.java'))
    return {name: HERE / 'java' / name for name in names}


def oracle_identity(behavior=oracle):
    names = getattr(behavior, 'ORACLE_FILES', ('oracle.py', 'scenario.json'))
    return identity({name: HERE / name for name in names})


def case_steps(case, behavior=oracle):
    return case['amounts'] if behavior is oracle else behavior.steps(case)


class ExecutionProblem(RuntimeError):
    pass


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def identity(paths):
    files = {name: digest(path) for name, path in sorted(paths.items())}
    return dict(sha256=hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(), files=files)


def environment(java_home):
    result = {key: value for key, value in os.environ.items()
              if key in {'PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'SYSTEMROOT'}}
    result['JAVA_HOME'] = str(java_home)
    result['PATH'] = str(java_home / 'bin') + os.pathsep + result.get('PATH', '')
    return result


def stop(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def command(arguments, env, timeout, log):
    """Bound subprocess duration and combined output, including Maven/JVM diagnostics."""
    process = subprocess.Popen(arguments, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               stdin=subprocess.DEVNULL, start_new_session=True)
    deadline, size = time.monotonic() + timeout, 0
    try:
        with selectors.DefaultSelector() as selector, log.open('wb') as output:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ExecutionProblem('Subprocess timeout: ' + log.name)
                if not selector.select(min(remaining, 0.25)):
                    continue
                data = os.read(process.stdout.fileno(), 8192)
                if not data:
                    selector.unregister(process.stdout)
                    continue
                size += len(data)
                if size > MAX_OUTPUT:
                    raise ExecutionProblem('Subprocess output limit: ' + log.name)
                output.write(data)
        return process.wait(timeout=max(0.01, deadline - time.monotonic()))
    except subprocess.TimeoutExpired as error:
        raise ExecutionProblem('Subprocess timeout: ' + log.name) from error
    finally:
        stop(process)
        process.stdout.close()


def generate(rng, max_length):
    """Uses only bounds and its own expected accumulated state, never subject metadata."""
    capture = rng.randint(2, 10000)
    amounts, accumulated = [], 0
    for _ in range(rng.randint(1, max_length)):
        remaining = capture - accumulated
        boundaries = [-1, 0, 1, capture - 1, capture, capture + 1,
                      remaining - 1, remaining, remaining + 1]
        amount = rng.choice(boundaries + [rng.randint(1, capture), rng.randint(-1, 10001)])
        amounts.append(amount)
        if 0 < amount <= remaining:
            accumulated += amount
    return dict(capture=capture, amounts=amounts)


class Runner:
    def __init__(self, project, env, timeout, deadline, counts, behavior=oracle):
        self.behavior = behavior
        self.process = subprocess.Popen(
            [str(Path(env['JAVA_HOME']) / 'bin/java'), '-Xmx64m', '-cp', str(project / 'target/classes'),
             getattr(behavior, 'ENTRYPOINT', 'experiment.refunds.ScenarioRunner')], env=env, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ, 'stdout')
        self.selector.register(self.process.stderr, selectors.EVENT_READ, 'stderr')
        self.timeout, self.deadline, self.counts = timeout, deadline, counts
        self.stderr_bytes = 0

    def close(self):
        stop(self.process)
        self.selector.close()
        for stream in [self.process.stdin, self.process.stdout, self.process.stderr]:
            stream.close()

    def execute(self, case, phase):
        self.behavior.validate(case)
        if time.monotonic() >= self.deadline:
            raise ExecutionProblem('Total search runtime exhausted')
        self.counts[phase + '_attempted'] += 1
        self.counts['operations_requested'] += len(case_steps(case, self.behavior))
        if self.behavior is oracle:
            line = '\t'.join(str(value) for value in [case['capture'], *case['amounts']]) + '\n'
        else:
            line = self.behavior.encode(case)
        try:
            self.process.stdin.write(line.encode())
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise ExecutionProblem('Java runner unavailable') from error
        response = bytearray()
        deadline = min(self.deadline, time.monotonic() + self.timeout)
        while b'\n' not in response:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ExecutionProblem('Scenario timeout')
            for key, _ in self.selector.select(min(remaining, 0.25)):
                data = os.read(key.fileobj.fileno(), MAX_RESPONSE + 1)
                if not data:
                    raise ExecutionProblem('Java runner exited or closed output')
                if key.data == 'stderr':
                    self.stderr_bytes += len(data)
                    if self.stderr_bytes > MAX_OUTPUT:
                        raise ExecutionProblem('Java stderr output limit')
                else:
                    response.extend(data)
                    if len(response) > MAX_RESPONSE:
                        raise ExecutionProblem('Java protocol output limit')
        try:
            trace = self.parse(response.decode(), len(case_steps(case, self.behavior)),
                               getattr(self.behavior, 'OBSERVATIONS', ('refunded', 'effects')))
            violation = self.behavior.evaluate(case, trace)
        except (ValueError, UnicodeError, IndexError) as error:
            raise ExecutionProblem('Invalid Java observations: ' + str(error)) from error
        self.counts[phase + '_completed'] += 1
        self.counts['operations_completed'] += len(case_steps(case, self.behavior))
        return dict(scenario=case, trace=trace, violation=violation)

    @staticmethod
    def parse(response, operations, observation_fields=('refunded', 'effects')):
        if not response.endswith('\n') or response.count('\n') != 1:
            raise ValueError('Expected exactly one protocol response')
        fields = response.rstrip('\n').split('\t')
        if fields[0] == 'execution_error':
            raise ExecutionProblem('Java execution exception: ' + fields[-1])
        if fields[0] != 'ok' or len(fields) != operations + 2:
            raise ValueError('Missing scenario results')
        initial = fields[1].split(',')
        if len(initial) != 2:
            raise ValueError('Invalid initial observation')
        trace = [dict(zip(observation_fields, map(int, initial)))]
        for field in fields[2:]:
            status, refunded, effects = field.split(',')
            if status not in {'accepted', 'rejected'}:
                raise ValueError('Unknown result classification')
            trace.append(dict(status=status, **dict(zip(observation_fields, [int(refunded), int(effects)]))))
        return trace


def cap_candidates(current):
    candidates = []
    if len(current['amounts']) > 1:
        for index in range(len(current['amounts'])):
            candidates.append(dict(capture=current['capture'],
                                   amounts=current['amounts'][:index] + current['amounts'][index + 1:]))
    for capture in sorted({2, current['capture'] // 2, current['capture'] - 1}):
        if 2 <= capture < current['capture']:
            # Shrink related values together, then ask the independent oracle
            # whether the original obligation still fails under valid inputs.
            candidates.append(dict(capture=capture,
                                   amounts=[min(amount, capture) for amount in current['amounts']]))
            candidates.append(dict(capture=capture, amounts=current['amounts'][:]))
    for index, value in enumerate(current['amounts']):
        # Strictly decrease absolute magnitude to prevent reduction cycles.
        for amount in sorted({0, 1, value // 2, value - 1, current['capture'] - 1,
                              current['capture']}):
            if -1 <= amount <= 10001 and abs(amount) < abs(value):
                values = current['amounts'][:]
                values[index] = amount
                candidates.append(dict(capture=current['capture'], amounts=values))
    return candidates


def reduce_case(case, expectation, execute, attempts, seconds, candidate_factory=cap_candidates):
    """Greedy deletion and integer simplification; no global minimality claim."""
    current, used = case, 0
    deadline = time.monotonic() + seconds

    def preserves(candidate):
        nonlocal used
        if used >= attempts or time.monotonic() >= deadline:
            return None
        used += 1
        result = execute(candidate)
        return bool(result['violation'] and result['violation']['expectation'] == expectation)

    changed = True
    while changed:
        changed = False
        candidates = candidate_factory(current)
        for candidate in candidates:
            preserved = preserves(candidate)
            if preserved is None:
                return current, dict(attempts=used, completed=False, reason='reduction_budget')
            if preserved:
                current, changed = candidate, True
                break
    return current, dict(attempts=used, completed=True, reason='local_fixed_point')


def prepare(subject, output, env, timeout, behavior=oracle):
    project = output / 'subject'
    (project / subject_paths(behavior)[0].parent).mkdir(parents=True)
    # Snapshot only the Maven descriptor and implementation. Existing tests and labels
    # never enter the search project; a failed build cannot fall back to old classes.
    for path in subject_paths(behavior):
        shutil.copyfile(subject / path, project / path)
    adapter = project / 'src/main/java/experiment/refunds'
    adapter.mkdir(parents=True)
    for path in adapter_paths(behavior).values():
        shutil.copyfile(path, adapter / path.name)
    before = identity({str(path): project / path for path in subject_paths(behavior)})
    result = command(['mvn', '--offline', '--batch-mode', '--no-transfer-progress', '-f',
                      str(project / 'pom.xml'), 'compile'],
                     env, timeout, output / 'build.log')
    if result != 0:
        raise ExecutionProblem('Subject build failed; no execution permitted (build.log)')
    return project, before


def export_replay(output, case, expectation, report, filename='replay.json', behavior=oracle):
    bundle = dict(schema_version=1, behavior=getattr(behavior, 'NAME', 'refund-cap'), scenario=case, expectation=expectation,
                  subject=report['subject'], oracle=report['oracle'], adapter=report['adapter'])
    write_json(output / filename, bundle)
    (output / getattr(behavior, 'TEST_FILE', Path('GeneratedRefundBehaviorTest.java')).name).write_text(behavior.junit(case))


def run(subject, output, java_home, seed=17, scenarios=100, max_length=12,
        runtime=30, reduction_attempts=200, reduction_seconds=5,
        scenario_timeout=2, build_timeout=120, replay=None, behavior=oracle, replay_expectation=None):
    if not 1 <= scenarios <= 10000 or not 1 <= max_length <= 16:
        raise ValueError('Search bounds exceed experiment limits')
    if not (1 <= runtime <= 300 and 0 <= reduction_attempts <= 1000
            and 0 <= reduction_seconds <= 30 and 0 < scenario_timeout <= 10
            and 1 <= build_timeout <= 180):
        raise ValueError('Runtime/output/reduction limits exceed experiment limits')
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = environment(java_home)
    counts = Counter()
    report = dict(schema_version=1, outcome='incomplete', search_finished=False,
                  trusted_ci_provenance=False, behavior=getattr(behavior, 'NAME', 'refund-cap'),
                  generator=getattr(behavior, 'GENERATOR_VERSION', GENERATOR_VERSION), seed=seed,
                  budgets=dict(scenarios=scenarios, max_operations=max_length, search_seconds=runtime,
                               reduction_attempts=reduction_attempts, reduction_seconds=reduction_seconds,
                               reduction_inflight_seconds=scenario_timeout,
                               scenario_seconds=scenario_timeout, build_seconds=build_timeout,
                               subprocess_output_bytes=MAX_OUTPUT, response_bytes=MAX_RESPONSE),
                  counts=counts, problems=[], non_vacuity=[], finding=None,
                  oracle=oracle_identity(behavior),
                  adapter=identity(adapter_paths(behavior)),
                  search=identity({'search.py': HERE / 'search.py', 'sequence_strategy.py': HERE / 'sequence_strategy.py'}),
                  python_runtime=platform.python_version(),
                  assumptions=behavior.DEFINITION['scope'])
    started, runner = time.monotonic(), None
    try:
        report['subject'] = identity({str(path): subject / path for path in subject_paths(behavior)})
        counts['build_attempted'] += 1
        project, report['subject'] = prepare(subject, output, env, build_timeout, behavior)
        counts['build_completed'] += 1
        report['compiled_classes'] = identity({str(path.relative_to(project / 'target/classes')): path
                                               for path in (project / 'target/classes').rglob('*.class')})
        # Keep runtime evidence next to immutable input snapshots; never capture host paths/env.
        if command([str(java_home / 'bin/java'), '-version'], env, 5, output / 'runtime.txt') != 0:
            raise ExecutionProblem('Selected Java runtime unavailable')
        report['java_runtime'] = (output / 'runtime.txt').read_text().strip()
        search_started = time.monotonic()
        runner = Runner(project, env, scenario_timeout, search_started + runtime, counts, behavior)
        for case in behavior.non_vacuity_cases():
            result = runner.execute(case, 'non_vacuity')
            report['non_vacuity'].append(result)
            if result['violation']:
                raise ExecutionProblem('Non-vacuity/suitability check failed: ' + result['violation']['expectation'])
        rng, failure = random.Random(seed), None
        search_trace = hashlib.sha256()
        for _ in range(scenarios):
            case = replay if replay is not None else getattr(behavior, 'generate', generate)(rng, max_length)
            result = runner.execute(case, 'search')
            search_trace.update(json.dumps(result, sort_keys=True).encode() + b'\n')
            if result['violation']:
                failure = result
                break
            if replay is not None:
                break
        report['search_finished'] = True
        report['search_trace_sha256'] = search_trace.hexdigest()
        if replay_expectation is not None and (not failure or failure['violation']['expectation'] != replay_expectation):
            raise ExecutionProblem('Replay did not reproduce its declared expectation')
        report['termination'] = 'scenario_budget'
        if replay is not None:
            report['termination'] = 'replay_completed'
        if failure:
            report['termination'] = 'first_violation'
            expectation = failure['violation']['expectation']
            # Validate original failure before spending a bounded reduction budget.
            reproduced = runner.execute(failure['scenario'], 'original_replay')
            if reproduced != failure:
                raise ExecutionProblem('Original failure did not reproduce')
            report['finding'] = dict(expectation=expectation, original=failure, original_replay=reproduced)
            report['outcome'] = 'counterexample_found'
            # A later reducer problem must not make a verified original impossible to replay.
            export_replay(output, failure['scenario'], expectation, report, 'original-replay.json', behavior)
            export_replay(output, failure['scenario'], expectation, report, behavior=behavior)
            reduced, reduction = reduce_case(failure['scenario'], expectation,
                                            lambda case: runner.execute(case, 'reduction'),
                                            reduction_attempts, reduction_seconds, getattr(behavior, 'candidates', cap_candidates))
            report['reduction'] = reduction
            reduced_result = runner.execute(reduced, 'reduced_replay')
            repeated = runner.execute(reduced, 'reduced_replay')
            if (reduced_result != repeated or not reduced_result['violation']
                    or reduced_result['violation']['expectation'] != expectation):
                raise ExecutionProblem('Reduced failure did not reproduce')
            report['finding']['reduced'] = reduced_result
            report['finding']['reduced_replay'] = repeated
            export_replay(output, reduced, expectation, report, behavior=behavior)
        else:
            report['outcome'] = 'no_counterexample_within_budget'
        report['search_elapsed_seconds'] = round(time.monotonic() - search_started, 3)
    except (ExecutionProblem, OSError, ValueError) as error:
        report['problems'].append(str(error))
        # A verified original finding remains valid when subsequent work is unfinished.
        if report['finding'] is None:
            report['outcome'] = 'incomplete'
        report['unfinished_work'] = True
    finally:
        if runner is not None:
            runner.close()
        report['elapsed_seconds'] = round(time.monotonic() - started, 3)
        write_json(output / 'report.json', report)
        (output / 'report.md').write_text(markdown(report, behavior))
    return report


def markdown(report, behavior=oracle):
    if behavior is not oracle:
        return behavior_markdown(report, behavior)
    lines = ['# Local refund behavior search', '', 'Outcome: `' + report['outcome'] + '`.', '']
    if report['finding']:
        finding = report['finding']
        lines += ['Expectation: `' + finding['expectation'] + '`.', '']
        example = finding.get('reduced', finding['original'])
        case, violation = example['scenario'], example['violation']
        if finding['expectation'] == oracle.CAP:
            lines += [f"Against a capture of **{case['capture']} minor units**, the component settled "
                      f"**{violation['observed']['refunded']}** by step **{violation['step']}**, "
                      'exceeding the approved capture limit.', '']
        lines += [f"Initial state: capture {case['capture']}, refunded 0, settled effects 0.", '',
                  '| Step | Requested refund | Expected result | Observed result | Refunded total | Effects |',
                  '|---:|---:|---|---|---:|---:|']
        for step, amount in enumerate(case['amounts'], 1):
            before, after = example['trace'][step - 1], example['trace'][step]
            expected = 'rejected'
            if 0 < amount <= case['capture'] - before['refunded']:
                expected = 'accepted'
            marker = ' **first violation**' if step == violation['step'] else ''
            lines += [f"| {step}{marker} | {amount} | {expected} | {after['status']} | "
                      f"{after['refunded']} | {after['effects']} |"]
            if step == violation['step']:
                break
        lines += ['']
        for name in ['original', 'reduced']:
            if name not in finding:
                continue
            result = finding[name]
            lines += [f"{name.capitalize()} scenario: capture {result['scenario']['capture']}; "
                      f"refunds {result['scenario']['amounts']}.", '',
                      f"First violation at step {result['violation']['step']}: "
                      f"expected `{result['violation']['expected']}`; "
                      f"observed `{result['violation']['observed']}`.", '']
        lines += ['See `replay.json`, `GeneratedRefundBehaviorTest.java`, and full traces in `report.json`.', '']
    else:
        lines += ['No failure has been established. A completed bounded search does not prove correctness.', '']
    if report.get('reduction', {}).get('completed') is False:
        lines += ['Reduction stopped at its budget; the result is reduced, not globally minimal.', '']
    lines += ['Search termination: `' + report.get('termination', 'unfinished') + '`.', '']
    if report.get('unfinished_work'):
        lines += ['Unfinished work: execution stopped; inspect the problems below. '
                  'Any retained finding has a verified original replay.', '']
    lines += ['Execution problem: ' + problem for problem in report['problems']]
    lines += ['', 'Sequential owned fixture only. Local observations, no trusted CI provenance.', '',
              f"Seed: {report['seed']}. Generator: {report['generator']}.",
              f"Executed scenarios by phase: `{dict(report['counts'])}`.", '',
              'Scope and assumptions: ' + report['assumptions'], '']
    return '\n'.join(lines) + '\n'


def behavior_markdown(report, behavior):
    lines = ['# Local behavior search: ' + report['behavior'], '',
             'Outcome: `' + report['outcome'] + '`.', '']
    if report['finding']:
        finding = report['finding']
        lines += ['Failed expectation: `' + finding['expectation'] + '`.', '']
        for name in ['original', 'reduced']:
            if name in finding:
                result = finding[name]
                violation = result['violation']
                lines += [name.capitalize() + ': ' + behavior.summary(result['scenario']), '',
                          f"First violation at step {violation['step']}: expected `{violation['expected']}`; "
                          f"observed `{violation['observed']}`.", '']
        lines += ['Replay: `replay.json`. Draft: `' + behavior.TEST_FILE.name + '`.', '']
    else:
        lines += ['No reproducible violation established. A bounded search does not prove correctness.', '']
    lines += ['Scope: ' + report['assumptions'], '',
              f"Seed: {report['seed']}; generator: {report['generator']}.",
              f"Counts: `{dict(report['counts'])}`.", '',
              'Termination: ' + report.get('termination', 'unfinished') + '.', '']
    if report.get('reduction', {}).get('completed') is False:
        lines += ['Reduction stopped within its budget; no global minimality claim.', '']
    lines += ['Execution problem: ' + problem for problem in report['problems']]
    lines += ['', 'Local observations; no trusted CI provenance.', '']
    return '\n'.join(lines)


def run_junit(project, source, output, java_home, behavior=oracle, expectation=None):
    """Execute one exported draft; errors, skips or missing results are never assertion failures."""
    target = project / getattr(behavior, 'TEST_FILE', Path('src/test/java/example/refunds/GeneratedRefundBehaviorTest.java'))
    test_class = getattr(behavior, 'TEST_CLASS', 'example.refunds.GeneratedRefundBehaviorTest')
    test_method = getattr(behavior, 'TEST_METHOD', 'refundsRespectCaptureAndAcceptance')
    expectation = expectation or oracle.CAP
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(source.read_text())
    reports = project / 'target/surefire-reports'
    if reports.exists():
        raise ExecutionProblem('Refusing stale JUnit results')
    code = command(['mvn', '--offline', '--batch-mode', '--no-transfer-progress', '-f',
                    str(project / 'pom.xml'), '-Dtest=' + test_class, 'test'],
                   environment(java_home), 120, output / 'junit.log')
    paths = list(reports.glob('TEST-*.xml'))
    if len(paths) != 1 or paths[0].stat().st_size > MAX_OUTPUT:
        raise ExecutionProblem('Missing or excessive JUnit reports')
    suite = ET.parse(paths[0]).getroot()
    tests = suite.findall('testcase')
    if (len(tests) != 1 or tests[0].get('name') != test_method or tests[0].get('classname') != test_class
            or tests[0].find('error') is not None or tests[0].find('skipped') is not None):
        raise ExecutionProblem('JUnit did not execute exactly the exported test without errors')
    failure = tests[0].find('failure')
    if failure is None and code == 0:
        status = 'passed'
    elif (failure is not None and code != 0
          and failure.get('type') == 'org.opentest4j.AssertionFailedError'
          and expectation in failure.get('message', '')):
        status = 'aggregate_cap_assertion_failed' if expectation == oracle.CAP else 'expectation_assertion_failed'
    else:
        raise ExecutionProblem('JUnit failure was not the expected business assertion')
    result = dict(status=status, exit_code=code, test_sha256=digest(source),
                  report_sha256=digest(paths[0]), assertion_message=None)
    if failure is not None:
        result['assertion_message'] = failure.get('message')
    write_json(output / 'junit.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--subject', type=Path, required=True, help='Owned, reviewed refund Maven fixture')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--seed', type=int, default=17)
    parser.add_argument('--scenarios', type=int, default=100)
    parser.add_argument('--max-length', type=int, default=12)
    parser.add_argument('--runtime', type=float, default=30)
    parser.add_argument('--replay', type=Path)
    parser.add_argument('--behavior', choices=['refund-cap', 'refund-retry', 'ticket-transfer'], default='refund-cap')
    args = parser.parse_args()
    behavior = oracle
    if args.behavior != 'refund-cap':
        behavior = importlib.import_module({'refund-retry': 'retry_behavior', 'ticket-transfer': 'ticket_behavior'}[args.behavior])
    replay = None
    bundle = None
    if args.replay:
        if args.replay.stat().st_size > MAX_OUTPUT:
            parser.error('Replay size limit')
        bundle = json.loads(args.replay.read_text())
        replay = bundle['scenario']
        behavior.validate(replay)
        if bundle.get('behavior', 'refund-cap') != args.behavior:
            parser.error('Replay behavior mismatch')
        actual_oracle = oracle_identity(behavior)
        actual_adapter = identity(adapter_paths(behavior))
        actual_subject = identity({str(path): args.subject / path for path in subject_paths(behavior)})
        if any(bundle[key] != value for key, value in [('subject', actual_subject),
                                                      ('oracle', actual_oracle), ('adapter', actual_adapter)]):
            parser.error('Replay subject/oracle/adapter identity mismatch')
    report = run(args.subject, args.output, args.java_home, seed=args.seed, scenarios=args.scenarios,
                 max_length=args.max_length, runtime=args.runtime, replay=replay, behavior=behavior,
                 replay_expectation=bundle['expectation'] if bundle else None)
    print(report['outcome'] + ': ' + str(args.output / 'report.md'))
    if report['problems']:
        raise SystemExit(2)


if __name__ == '__main__':
    main()
