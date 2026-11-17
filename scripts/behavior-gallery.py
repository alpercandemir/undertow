#!/usr/bin/env python3
"""Execute eight authored cases and summarize observed business outcomes in CI."""

import argparse
from collections import Counter
import difflib
import importlib.util
import json
from pathlib import Path
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('behavior_review', ROOT / 'scripts/behavior-review.py')
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)
search, explore = review.search, review.explore
CATALOG = ROOT / 'docs/cases/behavior-gallery.json'


def mutation(source, name):
    retry_return = '            return;\n        }\n        if (amount <= 0'
    replacements = {
        'refund-double-charge': (retry_return, '            if (amount <= captured - refunded) { refunded += amount; }\n' + retry_return),
        'refund-over-cap': ('amount > captured - refunded', 'amount > captured'),
        'refund-key-conflict': ('if (settled.get(operationKey) != amount) throw new IllegalArgumentException("Key reused with another amount");',
                                '// Authored defect: conflicting amounts are treated as successful retries.'),
        'ticket-preview-transfer': ('return new Preview(', 'owner = recipient; accepted = true;\n        return new Preview('),
        'ticket-unauthorized': ('if (!recipient.equals(signedInAccount))', 'if (false && !recipient.equals(signedInAccount))'),
        'refund-build-error': ('refunded += amount;', 'refunded += gallery_missing_symbol;'),
    }
    if name == 'none':
        return source
    old, new = replacements[name]
    review.require(source.count(old) == 1, 'Fixture changed; review the authored mutation: ' + name)
    return source.replace(old, new)


def observed_status(decision):
    if decision['exit_code'] == 2:
        return 'INCOMPLETE'
    if decision.get('search_outcome') == 'counterexample_found' and decision['exit_code'] == 1:
        return 'VIOLATION'
    if decision['exit_code'] == 0 and decision.get('search_outcome') == 'no_counterexample_within_budget':
        return 'PASS'
    return 'EXISTING TEST FAILURE'


def validate_observation(case, decision, report):
    actual = observed_status(decision)
    review.require(actual == case['expected'], f"Expected {case['expected']}, observed {actual}")
    if actual == 'VIOLATION':
        review.require(report['finding']['expectation'] == case['expectation'], 'A different business rule failed')
        review.require(decision.get('replay') == 'reproduced', 'Missing confirmed replay')
        review.require(decision.get('candidate_junit', {}).get('status', '').endswith('assertion_failed'),
                       'Missing generated business assertion failure')
        review.require(not decision['problems'], 'Finding has unfinished validation')
    elif actual == 'PASS':
        review.require(decision['baseline']['status'] == 'passed' and report['finding'] is None,
                       'Happy path has a failing baseline or a finding')
        review.require(report['counts']['search_completed'] == report['budgets']['scenarios'],
                       'Happy-path bounded search did not finish')


def walkthrough(case, project, java_home):
    behavior = explore.PROFILES[case['profile']]
    runner = search.Runner(project, search.environment(java_home), 2, time.monotonic() + 30, Counter(), behavior)
    try:
        observed = runner.execute(case['walkthrough'], 'walkthrough')
    finally:
        runner.close()
    review.require(observed['violation'] is None and observed['trace'] == case['expected_trace'],
                   'Happy-path walkthrough did not match its expected actual states')
    return observed


def validate_build_problem(log, decision, report):
    review.require('COMPILATION ERROR' in log and 'gallery_missing_symbol' in log,
                   'Incomplete result was not the intended compiler error; inspect the log')
    review.require(not report.get('finding') and 'candidate_junit' not in decision,
                   'Build failure was incorrectly presented as a business violation')


def correction_check(case, fixture, found, output, java_home):
    behavior = explore.PROFILES[case['profile']]
    owned = output / 'corrected-component'
    review.copy_component(fixture, owned)
    code = explore.main(['--adopt', str(found), '--subject', str(owned), '--output', str(output / 'adoption')])
    review.require(code == 0, 'Correction could not adopt the generated test')
    proposal = json.loads((found / 'contract-draft.json').read_text())
    result = review.suite(owned, java_home, output / 'correction-suite.log', proposal['test_id'])
    review.require(result['status'] == 'passed', 'Full correction suite failed')
    review.require(search.digest(owned / explore.test_path(behavior)) == proposal['test_sha256'],
                   'Correction used a different generated test')
    return result


def run_case(case, output, java_home, catalog):
    output.mkdir()
    row = dict(id=case['id'], title=case['title'], rule=case['rule'], profile=case['profile'], expected=case['expected'],
               observed='NOT RUN', validation='failed', problems=[])
    try:
        behavior = explore.PROFILES[case['profile']]
        fixture = explore.fixture_root(case['profile'])
        subject = output / 'input'
        review.copy_component(fixture, subject)
        main = subject / search.subject_paths(behavior)[0]
        original = main.read_text()
        changed = mutation(original, case['change'])
        main.write_text(changed)
        (output / 'authored-change.patch').write_text(''.join(difflib.unified_diff(
            original.splitlines(keepends=True), changed.splitlines(keepends=True),
            fromfile='a/' + main.relative_to(subject).as_posix(), tofile='b/' + main.relative_to(subject).as_posix())))
        run = output / 'review'
        code = review.main(['--subject', str(subject), '--behavior', case['profile'], '--java-home', str(java_home),
                            '--seed', str(catalog['seed']), '--scenarios', str(catalog['scenarios']), '--output', str(run)])
        decision = json.loads((run / 'decision.json').read_text())
        review.require(code == decision['exit_code'], 'Exit code and decision disagree')
        row['observed'] = observed_status(decision)
        row['baseline'] = decision.get('baseline', {}).get('status', 'not executed')
        path = run / 'search/report.json'
        report = json.loads(path.read_text()) if path.exists() else {}
        validate_observation(case, decision, report)
        if row['observed'] == 'PASS':
            row['walkthrough'] = walkthrough(case, run / 'search/subject', java_home)
            search.write_json(output / 'walkthrough.json', row['walkthrough'])
        elif row['observed'] == 'VIOLATION':
            row['finding'] = explore.selected_finding(report)
            row['correction'] = correction_check(case, fixture, run / 'search', output, java_home)
        else:
            log = (run / 'baseline.log').read_text()
            validate_build_problem(log, decision, report)
            row['diagnostic'] = 'Java compilation failed: gallery_missing_symbol cannot be resolved. No business rule was evaluated.'
        row['validation'] = 'matched'
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, search.ExecutionProblem) as error:
        row['problems'].append(str(error))
    search.write_json(output / 'case.json', row)
    return row


def render(rows):
    matched = sum(row['validation'] == 'matched' for row in rows)
    lines = ['# Eight business-behavior cases', '',
             f'**Gallery verification: {matched}/8 cases matched their expected outcomes; {len(rows)}/8 completed.**', '',
             'These are authored examples executed as real Java: two happy paths, five business-rule violations and one build problem. '
             'A green gallery job means the examples behaved as labeled; the VIOLATION rows remain failures of business rules. '
             'Actual candidate checks in the separate Behavior review workflow still fail on violations.', '',
             '| Case | Business rule / expectation | Expected | Observed | Existing suite | Correction + regression | Verification |',
             '|---|---|---|---|---|---|---|']
    for row in rows:
        lines += [f"| [{row['title']}](#{row['id']}) | {row['rule']} | {row['expected']} | **{row['observed']}** | "
                  f"{row.get('baseline', 'not executed')} | {row.get('correction', {}).get('status', 'not applicable')} | {row['validation']} |"]
    lines += ['', 'PASS means the recorded walkthrough and bounded search passed, not proof of correctness. '
              'Rejected unauthorized, conflicting or over-limit requests in a happy path are correct behavior. '
              'INCOMPLETE establishes no business-rule verdict. All amounts use integer minor units.', '']
    for row in rows:
        lines += [f"<a id=\"{row['id']}\"></a>", '', f"## {row['title']}", '', f"**Observed: {row['observed']}** — {row['rule']}", '']
        selected = row.get('walkthrough') or row.get('finding')
        if selected:
            behavior = explore.PROFILES[row['profile']]
            initial = selected['scenario'].get('capture', selected['scenario'].get('initial'))
            if row['profile'].startswith('refund'):
                lines += [f'Captured payment: **{initial} minor units**.', '']
            lines += ['Initial state: ' + explore.state_text(selected['trace'][0]) + '.', '',
                      '| Step | Operation | Result | Observed state |', '|---:|---|---|---|']
            for index, operation in enumerate(search.case_steps(selected['scenario'], behavior), 1):
                state = selected['trace'][index]
                lines += [f"| {index} | {explore.operation_text(operation)} | {state['status']} | {explore.state_text(state)} |"]
            lines += ['']
            if selected['violation']:
                v = selected['violation']
                lines += [f"Broken rule: **`{v['expectation']}`**, first violated at step **{v['step']}**.", '',
                          'Expected: **' + v['expected']['status'] + '**; ' + explore.state_text(v['expected']) + '.', '',
                          'Observed: **' + v['observed']['status'] + '**; ' + explore.state_text(v['observed']) + '.', '',
                          'Exact-snapshot replay reproduced it. The generated JUnit assertion failed on this candidate.', '']
                if row.get('correction', {}).get('status') == 'passed':
                    lines += ['The identical test and the full correction suite passed.', '']
                else:
                    lines += ['Correction validation did not complete successfully; inspect the problems below.', '']
                lines += [
                          'Existing suite: **' + row['baseline'] + '**. ' + ('This adds a failure missed by existing tests.'
                          if row['baseline'] == 'passed' else 'Existing tests already fail; this case demonstrates a reduced explanation.'), '']
            else:
                lines += ['Every observed step matched the expected state. The separate bounded search completed without a finding.', '']
        if row.get('diagnostic'):
            lines += [row['diagnostic'], '']
        lines += ['- ' + p for p in row['problems']]
        lines += [f"Evidence folder: `{row['id']}/` in the gallery artifact (review summary, replay, test, patch and logs when available).", '']
    lines += ['## Reviewer decision', '',
              'Review the rule, first violating step and expected/observed difference. For a real change, request a fix, '
              'accept the proposed regression, revise the rule, or reject the finding with reasons. '
              'No commands are required to inspect this gallery. These authored examples do not measure independent accuracy or human time savings.', '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(argv)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    catalog = json.loads(CATALOG.read_text())
    rows = []
    search.write_json(output / 'catalog.json', catalog)
    for case in catalog['cases']:
        rows.append(run_case(case, output / case['id'], args.java_home.resolve(), catalog))
        search.write_json(output / 'gallery.json', dict(catalog_sha256=search.digest(CATALOG), cases=rows))
        (output / 'summary.md').write_text(render(rows))
        print(case['id'] + ': ' + rows[-1]['observed'] + ' (' + rows[-1]['validation'] + ')', flush=True)
    return 0 if len(rows) == 8 and all(row['validation'] == 'matched' for row in rows) else 1


if __name__ == '__main__':
    raise SystemExit(main())
