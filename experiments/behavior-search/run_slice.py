#!/usr/bin/env python3
"""Milestones 1–2 acceptance exercise. Subject preparation stays outside discovery."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import random
import time

import oracle
import search

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--seeds', type=int, nargs='+', default=[17, 29, 101])
    args = parser.parse_args()
    if not 1 <= len(args.seeds) <= 10 or len(set(args.seeds)) != len(args.seeds):
        parser.error('Supply one to ten distinct seeds')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    source = ROOT / 'demo/refund-extraction'
    code = (source / search.MAIN).read_text()
    guard = 'if (amount <= 0 || amount > captured - refunded)'
    if code.count(guard) != 1:
        raise ValueError('Owned fixture guard changed; review preparation before running')
    # Labels/mutations exist only in this acceptance driver, never in generation or reduction.
    subjects = {'subject-a': code.replace(guard, 'if (amount <= 0 || amount > captured)'),
                'subject-b': code}
    for name, content in subjects.items():
        path = output / 'inputs' / name
        (path / search.MAIN.parent).mkdir(parents=True)
        (path / search.MAIN).write_text(content)
        (path / 'pom.xml').write_bytes((source / 'pom.xml').read_bytes())
    runs = []
    for seed in args.seeds:
        for subject in subjects:
            destination = output / f'{subject}-seed-{seed}'
            report = search.run(output / 'inputs' / subject, destination, args.java_home, seed=seed)
            expected = 'counterexample_found' if subject == 'subject-a' else 'no_counterexample_within_budget'
            if report['outcome'] != expected or report['problems'] or not report['search_finished']:
                raise search.ExecutionProblem('Acceptance search failed: ' + str(destination / 'report.json'))
            if subject == 'subject-a':
                if report['finding']['expectation'] != oracle.CAP:
                    raise search.ExecutionProblem('Found a different obligation')
                if not report['reduction']['completed']:
                    raise search.ExecutionProblem('Default reduction did not finish')
            # Re-run the entire fixed-seed search in the same compiled subject with fresh
            # state per request. Compare all observations, not wall-clock measurements.
            counts = Counter()
            runner = search.Runner(destination / 'subject', search.environment(args.java_home), 2,
                                   time.monotonic() + 30, counts)
            try:
                rng = random.Random(seed)
                trace = hashlib.sha256()
                count = report['counts']['search_completed']
                for _ in range(count):
                    repeated = runner.execute(search.generate(rng, 12), 'repeatability')
                    trace.update(json.dumps(repeated, sort_keys=True).encode() + b'\n')
                    if repeated['violation']:
                        break
                target = report['finding']['original'] if report['finding'] else None
                if (counts['repeatability_completed'] != count or (target and repeated != target)
                        or trace.hexdigest() != report['search_trace_sha256']):
                    raise search.ExecutionProblem('Fixed-seed execution changed')
                if not target and repeated['violation']:
                    raise search.ExecutionProblem('Corrected fixed-seed execution changed')
            finally:
                runner.close()
            runs.append(dict(subject=subject, seed=seed, outcome=report['outcome'],
                             counts=dict(report['counts']), repeat_counts=dict(counts),
                             report=str(destination.relative_to(output) / 'report.md')))
            print(f"{subject}, seed {seed}: {report['outcome']}", flush=True)
    # Exactly the same discovered/reduced draft is executed on both subjects.
    first = args.seeds[0]
    exported = output / f'subject-a-seed-{first}/GeneratedRefundBehaviorTest.java'
    junit = {}
    for subject in subjects:
        destination = output / f'{subject}-seed-{first}'
        junit[subject] = search.run_junit(destination / 'subject', exported, destination, args.java_home)
    if (junit['subject-a']['status'] != 'aggregate_cap_assertion_failed'
            or junit['subject-b']['status'] != 'passed'):
        raise search.ExecutionProblem('Exported JUnit did not distinguish the subjects')
    summary = dict(kind='local-milestone-1-2-acceptance', trusted_ci_provenance=False,
                   subjects={'subject-a': 'cumulative-cap defect', 'subject-b': 'corrected remaining-cap guard'},
                   runs=runs, exported_junit=junit, elapsed_seconds=round(time.monotonic() - started, 3))
    search.write_json(output / 'acceptance.json', summary)
    lines = ['# Milestones 1–2 local acceptance', '',
             'The generated search found and reduced the cumulative-cap failure. '
             'The same exported JUnit test failed on its aggregate-cap assertion for subject A '
             'and passed for subject B.', '',
             '| Subject | Seed | Result | Example |', '|---|---:|---|---|']
    for run in runs:
        lines.append(f"| {run['subject']} | {run['seed']} | {run['outcome']} | [Report]({run['report']}) |")
    lines += ['', f"Elapsed: {summary['elapsed_seconds']} seconds. All fixed-seed searches repeated successfully.",
              '', 'Subject A contains the owned fixture cap-guard mutation; subject B is the correction. '
              'Names and expected outcomes are used only by this acceptance driver.', '',
              'These are local observations, not trusted CI receipts or evidence of general applicability. '
              'Stop here for product review before milestone 3.', '']
    (output / 'examples.md').write_text('\n'.join(lines))
    print('Examples: ' + str(output / 'examples.md'))


if __name__ == '__main__':
    main()
