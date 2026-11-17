#!/usr/bin/env python3
"""Internal milestone-3 validation after a strategy freeze, with existing-suite comparison."""

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import random
import shutil
import time

import retry_behavior
import search
import ticket_behavior

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
LOCKED_FILES = {'sequence_strategy.py', 'search.py', 'java/ExecutionAdapter.java', 'java/ScenarioProtocol.java'}
SPEC = importlib.util.spec_from_file_location('behavior_ci_formats', ROOT / 'scripts/ci-formats.py')
FORMATS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FORMATS)


def verify_lock(root=HERE):
    lock = json.loads((root / 'strategy-lock.json').read_text())
    if set(lock['files']) != LOCKED_FILES:
        raise ValueError('Strategy lock inventory changed')
    if any(search.digest(root / name) != expected for name, expected in lock['files'].items()):
        raise ValueError('Frozen strategy changed; this validation cannot retain its freeze claim')
    return lock


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError('Fixture preparation changed; review the mutation')
    return source.replace(old, new)


def candidates():
    """Labels and authored mutations live only in this host acceptance driver."""
    refund = (ROOT / 'demo/refund-extraction' / retry_behavior.MAIN).read_text()
    ticket = (ROOT / 'demo/ticket-transfer' / ticket_behavior.MAIN).read_text()
    retry_return = '            return;\n        }\n        if (amount <= 0'
    entries = [
        ('refund-correct', retry_behavior, refund, 'control'),
        ('refund-retry-rejected', retry_behavior,
         replace_once(refund, retry_return,
                      '            throw new IllegalArgumentException("Retry rejected");\n        }\n        if (amount <= 0'), 'defect'),
        ('refund-partial-retry-charged', retry_behavior,
         replace_once(refund, retry_return,
                      '            if (amount <= captured - refunded) { refunded += amount; }\n' + retry_return), 'defect'),
        ('ticket-correct', ticket_behavior, ticket, 'control'),
        ('ticket-preview-settles', ticket_behavior,
         replace_once(ticket, 'return new Preview(', 'owner = recipient; accepted = true;\n        return new Preview('), 'defect'),
        ('ticket-preview-after-settlement', ticket_behavior,
         replace_once(ticket, 'return new Preview(', 'if (accepted) { owner = "other"; }\n        return new Preview('), 'defect'),
        ('ticket-authorization-after-settlement', ticket_behavior,
         replace_once(ticket, 'if (!recipient.equals(signedInAccount))',
                      'if (accepted) { owner = signedInAccount; return; }\n        if (!recipient.equals(signedInAccount))'), 'defect'),
        ('ticket-copy-change', ticket_behavior,
         replace_once(ticket, '"Transfer invitation"', '"Accept your transfer"'), 'control'),
        ('refund-uncompilable', retry_behavior, refund + '\ninvalid source\n', 'invalid_candidate'),
        ('refund-reject-all', retry_behavior,
         replace_once(refund, 'if (operationKey == null',
                      'if (true) throw new IllegalArgumentException("Disabled");\n        if (operationKey == null'), 'unsuitable'),
        ('ticket-concurrent-request', ticket_behavior, ticket, 'scope_request'),
    ]
    return entries


def materialize(destination, fixture, behavior, code, tests=False):
    (destination / behavior.MAIN.parent).mkdir(parents=True)
    (destination / behavior.MAIN).write_text(code)
    (destination / 'pom.xml').write_bytes((fixture / 'pom.xml').read_bytes())
    if tests:
        shutil.copytree(fixture / 'src/test', destination / 'src/test')


def existing_suite(fixture, behavior, code, output, java_home):
    output.mkdir()
    project = output / 'subject'
    materialize(project, fixture, behavior, code, tests=True)
    expected = []
    for path in sorted((fixture / 'src/test').rglob('*.java')):
        # Expected identities are obtained from a separate pristine execution below,
        # rather than inferring test names with a Java-source regular expression.
        expected.append(str(path.relative_to(fixture)))
    result = dict(source_tests=search.identity({name: fixture / name for name in expected}),
                  status='incomplete', tests=[], problem=None)
    try:
        exit_code = search.command(['mvn', '--offline', '--batch-mode', '--no-transfer-progress',
                                    '-f', str(project / 'pom.xml'), 'test'], search.environment(java_home),
                                   120, output / 'test.log')
        paths = sorted((project / 'target/surefire-reports').glob('TEST-*.xml'))
        if not paths or sum(path.stat().st_size for path in paths) > search.MAX_OUTPUT:
            raise search.ExecutionProblem('Missing or excessive existing-suite results')
        for path in paths:
            result['tests'].extend(FORMATS.junit(path.read_bytes()))
        tests = result['tests']
        if (len({test['name'] for test in tests}) != len(tests)
                or any(test['status'] not in {'passed', 'failed'} for test in tests)):
            raise search.ExecutionProblem('Existing suite had errors, skips or duplicate executions')
        failed = sum(test['status'] == 'failed' for test in tests)
        if (exit_code == 0) != (failed == 0):
            raise search.ExecutionProblem('Existing suite exit code did not match observations')
        result.update(status='failed' if failed else 'passed', failed=failed,
                      report_hashes={path.name: search.digest(path) for path in paths})
    except (search.ExecutionProblem, OSError, ValueError) as error:
        result['problem'] = str(error)
    search.write_json(output / 'existing-suite.json', result)
    return result


def repeat_run(project, report, behavior, java_home):
    counts, trace = Counter(), hashlib.sha256()
    runner = search.Runner(project, search.environment(java_home), 2, time.monotonic() + 30, counts, behavior)
    try:
        rng = random.Random(report['seed'])
        for _ in range(report['counts']['search_completed']):
            result = runner.execute(behavior.generate(rng, report['budgets']['max_operations']), 'repeat')
            trace.update(json.dumps(result, sort_keys=True).encode() + b'\n')
        if trace.hexdigest() != report['search_trace_sha256']:
            raise search.ExecutionProblem('Search observations changed on a fresh JVM')
        if report['finding']:
            reduced = report['finding']['reduced']
            if runner.execute(reduced['scenario'], 'fresh_jvm_replay') != reduced:
                raise search.ExecutionProblem('Reduced failure changed on a fresh JVM')
    finally:
        runner.close()
    return dict(counts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lock = verify_lock()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    search.write_json(output / 'strategy-lock.json', lock)
    started = time.monotonic()
    summary = dict(kind='internal-milestone-3-validation', trusted_ci_provenance=False,
                   strategy_lock_sha256=search.digest(HERE / 'strategy-lock.json'), candidates=[],
                   independent_engineer_validation=False, time_to_understand_seconds=None,
                   clarity_assessment='Requires an independent reviewer; reduced operation counts are observations only.',
                   adapter_effort=dict(ticket_files={name: len((HERE / name).read_text().splitlines()) for name in
                       ['java/TicketAdapter.java', 'java/TicketScenarioRunner.java', 'ticket_behavior.py', 'ticket-scenario.json']},
                       engineering_minutes=None,
                       note='File footprint recorded; active authoring time was not measured. Frozen strategy/core reused unchanged.'),
                   problems=[])
    pristine_test_ids = {}
    for name, behavior, code, role in candidates():
        verify_lock()
        row = dict(candidate=name, component=behavior.NAME, role=role, runs=[],
                   author='implementation-session', preparation='after_strategy_freeze',
                   eligible_for_detection_claim=False, comparison=None)
        summary['candidates'].append(row)
        destination = output / name
        destination.mkdir()
        fixture = ROOT / 'demo' / ('refund-extraction' if behavior is retry_behavior else 'ticket-transfer')
        subject = destination / 'input'
        materialize(subject, fixture, behavior, code)
        row['subject'] = search.identity({str(path): subject / path for path in search.subject_paths(behavior)})
        if role == 'scope_request':
            row['exclusion'] = 'Concurrent/distributed request outside the sequential adapter; no concurrency mutant executed.'
            continue
        row['existing_suite'] = existing_suite(fixture, behavior, code, destination / 'existing', args.java_home)
        actual_ids = {test['name'] for test in row['existing_suite']['tests']}
        if name in {'refund-correct', 'ticket-correct'}:
            if row['existing_suite']['status'] != 'passed':
                raise search.ExecutionProblem('Pristine existing suite failed: ' + name)
            pristine_test_ids[behavior.NAME] = actual_ids
        elif row['existing_suite']['status'] != 'incomplete' and actual_ids != pristine_test_ids[behavior.NAME]:
            raise search.ExecutionProblem('Missing or unexpected existing test identities: ' + name)
        for seed in lock['validation_seeds']:
            run_output = destination / f'seed-{seed}'
            report = search.run(subject, run_output, args.java_home, seed=seed,
                                scenarios=lock['scenarios'], max_length=lock['max_operations'], behavior=behavior)
            entry = dict(seed=seed, outcome=report['outcome'], problems=report['problems'],
                         report=str(run_output.relative_to(output) / 'report.md'), counts=dict(report['counts']))
            row['runs'].append(entry)
            if report['outcome'] == 'incomplete':
                row['exclusion'] = '; '.join(report['problems'])
                break
            if report['problems']:
                summary['problems'].append(name + ': unfinished work after a finding')
                break
            try:
                entry['repeat_counts'] = repeat_run(run_output / 'subject', report, behavior, args.java_home)
            except search.ExecutionProblem as error:
                entry['problems'].append(str(error))
                summary['problems'].append(name + ': ' + str(error))
                row['exclusion'] = 'Fresh-JVM reproducibility failed'
                break
            if report['finding']:
                result = report['finding']['reduced']
                entry.update(expectation=result['violation']['expectation'], scenario=result['scenario'],
                             original_operations=len(behavior.steps(report['finding']['original']['scenario'])),
                             reduced_operations=len(behavior.steps(result['scenario'])),
                             reduction_completed=report['reduction']['completed'])
                if 'exported_junit' not in row:
                    draft = run_output / behavior.TEST_FILE.name
                    defective = search.run_junit(run_output / 'subject', draft, run_output,
                                                 args.java_home, behavior, entry['expectation'])
                    corrected_output = destination / 'export-on-correction'
                    corrected_output.mkdir()
                    corrected, _ = search.prepare(fixture, corrected_output, search.environment(args.java_home),
                                                  120, behavior)
                    corrected_result = search.run_junit(corrected, draft, corrected_output,
                                                       args.java_home, behavior, entry['expectation'])
                    row['exported_junit'] = dict(candidate=defective, correction=corrected_result)
                    if defective['status'] != 'expectation_assertion_failed' or corrected_result['status'] != 'passed':
                        summary['problems'].append(name + ': exported draft did not distinguish candidate/correction')
        complete = len(row['runs']) == len(lock['validation_seeds']) and all(not run['problems'] for run in row['runs'])
        found = complete and all(run['outcome'] == 'counterexample_found' for run in row['runs'])
        row['eligible_for_detection_claim'] = role == 'defect' and complete
        if role == 'defect' and complete:
            row['detected'] = found
            if not found:
                row['comparison'] = 'missed_within_budget'
            elif row['existing_suite']['status'] == 'passed':
                row['comparison'] = 'additional_failure'
            elif row['existing_suite']['status'] == 'failed':
                row['comparison'] = 'restates_existing_failure'
            else:
                row['comparison'] = 'existing_suite_incomplete'
        if role == 'control' and not (complete and all(run['outcome'] == 'no_counterexample_within_budget' for run in row['runs'])):
            summary['problems'].append(name + ': control did not complete without findings')
        print(name + ': ' + (row['comparison'] or row.get('exclusion', 'bounded control passed')), flush=True)
    verify_lock()
    rows = summary['candidates']
    summary['counts'] = dict(candidates=len(rows), eligible_defects=sum(row['eligible_for_detection_claim'] for row in rows),
                            detected=sum(row.get('detected', False) for row in rows),
                            missed=sum(row.get('detected') is False for row in rows),
                            excluded=sum('exclusion' in row for row in rows),
                            additional_failures=sum(row['comparison'] == 'additional_failure' for row in rows),
                            restated_failures=sum(row['comparison'] == 'restates_existing_failure' for row in rows),
                            existing_suite_incomplete=sum(row.get('existing_suite', {}).get('status') == 'incomplete' for row in rows),
                            non_reproducing_findings=sum('changed on a fresh JVM' in problem for problem in summary['problems']))
    summary['elapsed_seconds'] = round(time.monotonic() - started, 3)
    summary['technical_acceptance'] = not summary['problems'] and summary['counts']['missed'] == 0
    search.write_json(output / 'validation.json', summary)
    lines = ['# Milestone 3 internal validation', '',
             'Strategy hashes remained unchanged after the recorded freeze. These are self-authored local '
             'validation cases, not an independent engineer evaluation.', '',
             '| Candidate | Existing suite | Search comparison | Example |', '|---|---|---|---|']
    for row in rows:
        status = row.get('existing_suite', {}).get('status', 'not executed')
        result = row['comparison'] or row.get('exclusion', 'bounded control passed')
        example = '[Report](' + row['runs'][0]['report'] + ')' if row['runs'] else 'outside scope'
        lines.append(f"| {row['candidate']} | {status} | {result} | {example} |")
    lines += ['', 'Counts: `' + str(summary['counts']) + '`.', '',
              f"Elapsed: {summary['elapsed_seconds']} seconds, including compilation, existing suites and paired exports.", '',
              'Found cases were reduced, replayed in fresh state and on a fresh JVM, with matching full fixed-seed '
              'search traces. Each distinct candidate export was executed against candidate and correction.', '',
              'Adapter footprint: `' + str(summary['adapter_effort']['ticket_files']) + '`. Active authoring time was not measured.', '',
              'Clarity improvement and time to understand remain unmeasured. An independent engineer has not '
              'reviewed this exercise. Do not advance to packaging or general product claims on these results alone.', '']
    (output / 'examples.md').write_text('\n'.join(lines))
    print('Examples: ' + str(output / 'examples.md'))
    if not summary['technical_acceptance']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
