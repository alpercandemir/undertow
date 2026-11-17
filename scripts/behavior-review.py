#!/usr/bin/env python3
"""Run behavior review in CI and leave a decision, evidence and regression patch."""

import argparse
import difflib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'experiments/behavior-search'))
import explore
import search


def require(condition, message):
    if not condition:
        raise search.ExecutionProblem(message)


def copy_component(source, destination):
    """Supported standalone component inputs, excluding stale build products."""
    destination.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(source / 'pom.xml', destination / 'pom.xml')
    shutil.copytree(source / 'src', destination / 'src')


def suite_result(project, code, required_test=None):
    cases = []
    for path in (project / 'target/surefire-reports').glob('TEST-*.xml'):
        require(path.stat().st_size <= search.MAX_OUTPUT, 'Excessive JUnit report')
        cases.extend(ET.parse(path).getroot().iter('testcase'))
    failures = sum(case.find('failure') is not None for case in cases)
    errors = sum(case.find('error') is not None for case in cases)
    skipped = sum(case.find('skipped') is not None for case in cases)
    require(cases and not errors and skipped < len(cases), 'Suite did not execute usable tests without errors')
    require((code == 0 and not failures) or (code != 0 and failures), 'Build outcome does not match JUnit results')
    if required_test:
        selected = [case for case in cases if case.get('classname', '') + '.' + case.get('name', '') == required_test]
        require(len(selected) == 1 and all(selected[0].find(tag) is None for tag in ('failure', 'error', 'skipped')),
                'Adopted regression did not actually execute and pass')
    return dict(status='assertion_failed' if failures else 'passed', tests=len(cases),
                failures=failures, skipped=skipped, exit_code=code)


def suite(project, java_home, log, required_test=None):
    # The clean baseline also prepares dependencies for subsequent offline search.
    code = search.command(['mvn', '--batch-mode', '--no-transfer-progress', '-f',
                           str(project / 'pom.xml'), 'clean', 'test'],
                          search.environment(java_home), 180, log)
    return suite_result(project, code, required_test)


def git_identity(subject):
    def git(*args):
        result = subprocess.run(['git', '-C', str(subject), *args], capture_output=True, text=True, check=False)
        return result.stdout.strip() if result.returncode == 0 else None
    return dict(commit=git('rev-parse', 'HEAD'), dirty=bool(git('status', '--porcelain')))


def explore_run(arguments, output, java_home, expected=None):
    code = explore.main([*map(str, arguments), '--java-home', str(java_home), '--output', str(output)])
    require(code != 2 and (expected is None or code == expected),
            f'{output.name}: incomplete execution or unexpected outcome (exit {code}); inspect its report/logs')
    return json.loads((output / 'report.json').read_text())


def write_patch(output, behavior, prefix):
    source = output / 'search' / explore.test_path(behavior).name
    relative = Path(prefix) / explore.test_path(behavior)
    require(not relative.is_absolute() and '..' not in relative.parts, 'Patch path must be repository-relative')
    patch = ''.join(difflib.unified_diff([], source.read_text().splitlines(keepends=True),
                                       fromfile='/dev/null', tofile='b/' + relative.as_posix()))
    (output / 'regression.patch').write_text(patch)
    return source.read_text()


def conclusion(result):
    if result['problems']:
        return 'INCOMPLETE — inspect execution problems', 2
    found = result.get('search_outcome') == 'counterexample_found'
    if result['mode'] == 'authored calibration':
        return 'CALIBRATION PASSED — authored defect reproduced; correction passed', 0
    if found:
        return 'VIOLATION — review the rule and proposed regression', 1
    if result['baseline']['status'] != 'passed':
        return 'EXISTING TEST FAILURE — bounded search found no additional violation', 1
    return 'NO VIOLATION FOUND WITHIN BUDGET', 0


def summary(result, output):
    title, code = conclusion(result)
    lines = ['# Undertow behavior review', '', '**' + title + '**', '',
             'Mode: **' + result['mode'] + '**. Profile: `' + result['behavior'] + '`.', '',
             'Checked commit: `' + str(result['source']['commit']) + '`; local changes: `' + str(result['source']['dirty']) + '`.', '',
             '| Automated check | Observed result |', '|---|---|']
    for label, key in [('Existing suite', 'baseline'), ('Bounded search', 'search_outcome'),
                       ('Exact-snapshot replay', 'replay'), ('Generated JUnit on candidate', 'candidate_junit'),
                       ('Correction search', 'control'), ('Adopted test and full correction suite', 'adoption')]:
        value = result.get(key, 'not completed' if result['problems'] else 'not applicable')
        if isinstance(value, dict):
            value = value['status'] + (f" ({value['tests']} tests, {value['skipped']} skipped)" if 'tests' in value else '')
        lines.append(f'| {label} | {value} |')
    lines += ['', 'Elapsed automation time: ' + str(result['elapsed_seconds']) + ' seconds. Human investigation savings: **not measured**.', '']
    if result.get('search_outcome') == 'counterexample_found':
        if (result.get('baseline', {}).get('status') == 'passed'
                and result.get('candidate_junit', {}).get('status', '').endswith('assertion_failed')):
            lines += ['**Added coverage observed:** the existing suite passed; the generated test exposes the behavior violation.', '']
        elif result.get('baseline', {}).get('status') == 'assertion_failed':
            lines += ['The existing suite already fails. Whether this reproduction adds value requires review.', '']
        else:
            lines += ['A search finding is retained, but generated-test validation is incomplete.', '']
        report_path = output / 'search/report.md'
        if report_path.exists():
            # The wrapper report is bounded and already describes the trace in plain language.
            report = report_path.read_text().split('## Replay and adoption')[0]
            # Artifact-relative links do not resolve on the GitHub summary page.
            report = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'\1 (in evidence artifact: \2)', report)
            lines += ['## Reproduced case', '', report, '']
        if result.get('draft_source'):
            lines += ['<details><summary>Proposed regression test (unapproved)</summary>', '',
                      '```java', result['draft_source'].rstrip(), '```', '', '</details>', '']
            lines += ['The evidence artifact includes `regression.patch` for an implementation agent to apply after review.', '']
        if result.get('draft_note'):
            lines += [result['draft_note'], '']
    if result.get('counts'):
        lines += ['Scope: ' + result['scope'], '',
                  'Completed generated scenarios: ' + str(result['counts']['search_completed']) +
                  '; configured budget: ' + str(result['budgets']['scenarios']) +
                  '; seed: ' + str(result['seed']) + '.', '']
    if result['problems']:
        lines += ['## Execution problems', ''] + ['- ' + p.replace('\n', ' ') for p in result['problems']] + ['']
    lines += ['## Reviewer decision', '',
              '- Is the expectation and its sequential component scope correct?',
              '- If a violation was found: request a fix, revise the expectation, or reject the finding with a reason.',
              '- Keep or reject the proposed test through normal source review; record any benefit beyond existing tests.', '',
              'No terminal work is required from the reviewer. The implementation agent applies accepted changes and CI reruns.', '',
              'A completed bounded search is not proof of correctness. Calibration is self-authored evidence, not independent detection validation. '
              'No business policy is approved and no trusted contract receipt is issued by this workflow.', '']
    return '\n'.join(lines), code


def run(args, result):
    output, java_home = args.output.resolve(), args.java_home.resolve()
    behavior = explore.PROFILES[args.example or args.behavior]
    subject = args.subject.resolve() if args.subject else explore.fixture_root(args.example)
    result['source'] = git_identity(subject)
    tool_files = {p.relative_to(ROOT).as_posix(): p for p in (ROOT / 'experiments/behavior-search').rglob('*')
                  if p.is_file() and p.suffix in ('.py', '.json', '.java')}
    tool_files['scripts/behavior-review.py'] = Path(__file__).resolve()
    result['tool_inputs'] = search.identity(tool_files)
    if args.example:
        temporary = output / 'authored-input'
        temporary.mkdir()
        candidate, correction = explore.example_input(args.example, temporary)
        shutil.copytree(correction / 'src/test', candidate / 'src/test')
        subject = candidate
    baseline = output / 'baseline'
    copy_component(subject, baseline)
    result['component_inputs'] = search.identity({p.relative_to(baseline).as_posix(): p
                                                for p in baseline.rglob('*') if p.is_file()})
    result['baseline'] = suite(baseline, java_home, output / 'baseline.log')
    found = output / 'search'
    report = explore_run(['--subject', subject, '--behavior', result['behavior'],
                          '--seed', args.seed, '--scenarios', args.scenarios], found, java_home)
    result['search_outcome'] = report['outcome']
    result['counts'] = report['counts']
    result['budgets'] = report['budgets']
    result['scope'] = report['assumptions']
    result['seed'] = report['seed']
    result['oracle'] = report['oracle']
    result['adapter'] = report['adapter']
    if report['finding']:
        replay = explore_run(['--replay', found / 'replay.json'], output / 'replay', java_home, 1)
        require(explore.selected_finding(replay)['violation'] == explore.selected_finding(report)['violation'],
                'Replay changed the violation')
        result['replay'] = 'reproduced'
        expectation = report['finding']['expectation']
        draft = found / explore.test_path(behavior).name
        result['candidate_junit'] = search.run_junit(found / 'subject', draft, found, java_home, behavior, expectation)
        require(result['candidate_junit']['status'] != 'passed', 'Generated test did not expose the candidate violation')
        # Do not propose a patch that would overwrite a previously retained test.
        if (subject / explore.test_path(behavior)).exists():
            result['draft_note'] = 'Generated test path already exists; source review must reconcile it.'
        else:
            result['draft_source'] = write_patch(output, behavior, args.test_prefix)
    if args.example:
        require(report['finding'] is not None, 'Calibration missed its authored defect')
        require(result['baseline']['status'] == 'passed', 'Calibration no longer adds coverage beyond existing tests')
        control = explore_run(['--subject', correction, '--behavior', args.example,
                              '--seed', args.seed, '--scenarios', args.scenarios], output / 'control', java_home, 0)
        require(control['counts']['search_completed'] == args.scenarios, 'Control did not complete the search budget')
        result['control'] = control['outcome']
        owned = output / 'corrected-component'
        copy_component(correction, owned)
        code = explore.main(['--adopt', str(found), '--subject', str(owned), '--output', str(output / 'adoption')])
        require(code == 0, 'Draft could not be adopted in the disposable correction')
        proposal = json.loads((found / 'contract-draft.json').read_text())
        result['adoption'] = suite(owned, java_home, output / 'adoption.log', proposal['test_id'])
        require(result['adoption']['status'] == 'passed', 'Correction suite failed with the adopted test')
        require(search.digest(owned / explore.test_path(behavior)) == result['candidate_junit']['test_sha256'],
                'Candidate and correction executed different drafts')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument('--subject', type=Path)
    modes.add_argument('--example', choices=['refund-retry', 'ticket-transfer'])
    parser.add_argument('--behavior', choices=list(explore.PROFILES), default='refund-retry')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--test-prefix', default='.', help='Repository-relative component path for the proposed patch')
    parser.add_argument('--seed', type=int, default=17)
    parser.add_argument('--scenarios', type=int, default=100)
    args = parser.parse_args(argv)
    args.output.mkdir(parents=True, exist_ok=False)
    start = time.monotonic()
    result = dict(schema_version=1, mode='authored calibration' if args.example else 'candidate review',
                  behavior=args.example or args.behavior, source=dict(commit=None, dirty=None), problems=[],
                  owner_approval='not_recorded', trusted_ci_provenance=False,
                  run_id=os.environ.get('GITHUB_RUN_ID'), run_attempt=os.environ.get('GITHUB_RUN_ATTEMPT'))
    try:
        run(args, result)
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, search.ExecutionProblem) as error:
        result['problems'].append(str(error))
    result['elapsed_seconds'] = round(time.monotonic() - start, 2)
    markdown, code = summary(result, args.output)
    (args.output / 'summary.md').write_text(markdown)
    result.pop('draft_source', None)
    result['exit_code'] = code
    search.write_json(args.output / 'decision.json', result)
    print(conclusion(result)[0])
    return code


if __name__ == '__main__':
    raise SystemExit(main())
