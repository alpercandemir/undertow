#!/usr/bin/env python3
"""Local adoption interface around the unchanged, hash-identified search core."""

import argparse
import html
import json
from pathlib import Path
import shlex
import sys
import tempfile

# -I excludes the working directory, user site and PYTHONPATH. Only this trusted
# source/bundle directory supplies executable modules; subject inputs never do.
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import oracle
import retry_behavior
import search
import ticket_behavior

PROFILES = {'refund-cap': oracle, 'refund-retry': retry_behavior, 'ticket-transfer': ticket_behavior}
OWNER_NAMES = {0: 'sender', 1: 'recipient', 2: 'other account', 3: 'unrecognized owner'}


def test_path(behavior):
    return getattr(behavior, 'TEST_FILE', Path('src/test/java/example/refunds/GeneratedRefundBehaviorTest.java'))


def read_json(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > search.MAX_OUTPUT:
        raise ValueError('Expected a regular local JSON artifact of at most 1 MiB')
    return json.loads(path.read_text())


def replay_input(path, selected=None):
    bundle = read_json(path)
    name = bundle.get('behavior', 'refund-cap')
    if name not in PROFILES or selected is not None and selected != name:
        raise ValueError('Replay behavior does not match the selected profile')
    behavior = PROFILES[name]
    behavior.validate(bundle['scenario'])
    if (bundle['oracle'] != search.oracle_identity(behavior)
            or bundle['adapter'] != search.identity(search.adapter_paths(behavior))):
        raise ValueError('Replay oracle/adapter changed; use the recorded tool version')
    if not isinstance(bundle['expectation'], str):
        raise ValueError('Replay expectation is missing')
    return name, behavior, bundle


def fixture_root(name):
    component = 'ticket-transfer' if name == 'ticket-transfer' else 'refund-extraction'
    bundled = HERE / 'fixtures' / component
    if bundled.is_dir():
        return bundled
    return HERE.parents[1] / 'demo' / component


def example_input(name, temporary):
    behavior = PROFILES[name]
    fixture = fixture_root(name)
    main = search.subject_paths(behavior)[0]
    source = (fixture / main).read_text()
    if name == 'refund-cap':
        old, new = 'if (amount <= 0 || amount > captured - refunded)', 'if (amount <= 0 || amount > captured)'
    elif name == 'refund-retry':
        old = '            return;\n        }\n        if (amount <= 0'
        new = '            if (amount <= captured - refunded) { refunded += amount; }\n' + old
    else:
        old, new = 'return new Preview(', 'if (accepted) { owner = "other"; }\n        return new Preview('
    if source.count(old) != 1:
        raise ValueError('Bundled fixture changed; example preparation requires review')
    subject = temporary / 'example-input'
    (subject / main.parent).mkdir(parents=True)
    (subject / main).write_text(source.replace(old, new))
    (subject / 'pom.xml').write_bytes((fixture / 'pom.xml').read_bytes())
    return subject, fixture


def state_text(state):
    if 'owner' in state:
        return 'owner ' + OWNER_NAMES.get(state['owner'], 'unrecognized owner') + '; invitation ' + (
            'accepted' if state['accepted'] else 'pending')
    return f"refunded {state['refunded']} minor units; settled effects {state['effects']}"


def operation_text(operation):
    if isinstance(operation, int):
        return f'refund {operation}'
    if operation['action'] == 'refund':
        return f"refund {operation['amount']} with key {operation['key']}"
    if operation['action'] == 'preview':
        return 'preview'
    return 'accept as ' + operation['actor']


def selected_finding(report):
    finding = report.get('finding')
    if finding is None:
        return None
    return finding.get('reduced', finding['original'])


def draft_metadata(output, name, behavior, report):
    result = selected_finding(report)
    if result is None:
        return
    source = output / test_path(behavior).name
    proposal = dict(kind='local-regression-draft', schema_version=1, behavior=name,
                    review_status='unreviewed', trusted_ci_provenance=False,
                    expectation=result['violation']['expectation'], assumptions=report['assumptions'],
                    test_id=getattr(behavior, 'TEST_CLASS', 'example.refunds.GeneratedRefundBehaviorTest') + '.'
                    + getattr(behavior, 'TEST_METHOD', 'refundsRespectCaptureAndAcceptance'),
                    test_source=str(test_path(behavior)), test_sha256=search.digest(source),
                    oracle=report['oracle'], adapter=report['adapter'], subject=report['subject'])
    search.write_json(output / 'contract-draft.json', proposal)
    (output / 'review.md').write_text('''# Behavior review decision

Reviewer: unrecorded
CI run and checked commit: unrecorded
Expectation and adapter reviewed: pending
Finding decision (fix / revise rule / reject): pending
Draft test decision (keep / revise / reject): pending
Added value over existing tests: unassessed
Observed review effort: unmeasured

CI performs execution, replay and disposable adoption checks. The reviewer reads
the results and records a decision; manual command execution is not an acceptance gate.
Record reasons and evidence links. These fields do not grant
contract approval, trusted CI provenance or a completed independent pilot.
''')


def render(output, name, behavior, report, metadata):
    result = selected_finding(report)
    lines = ['# Undertow behavior search', '', '**Outcome: `' + report['outcome'] + '`**', '']
    if metadata.get('example'):
        lines += ['This is an intentionally defective owned example, executed as real Java.', '']
    if result:
        violation = result['violation']
        lines += ['Failed expectation: **' + violation['expectation'] + '**.', '',
                  f"First violation at step **{violation['step']}**.", '',
                  'Expected: **' + violation['expected']['status'] + '**; ' + state_text(violation['expected']) + '.',
                  'Observed: **' + violation['observed']['status'] + '**; ' + state_text(violation['observed']) + '.', '',
                  'Initial state: ' + state_text(result['trace'][0]) + '.', '']
        if 'capture' in result['scenario'] or name.startswith('refund'):
            capture = result['scenario'].get('capture', result['scenario'].get('initial'))
            lines += [f'Captured payment: **{capture} minor units**.', '']
        lines += ['| Step | Operation | Result | Observed state |', '|---:|---|---|---|']
        for index, operation in enumerate(search.case_steps(result['scenario'], behavior), 1):
            after = result['trace'][index]
            marker = ' (first violation)' if index == violation['step'] else ''
            lines += [f"| {index}{marker} | {operation_text(operation)} | {after['status']} | {state_text(after)} |"]
            if index == violation['step']:
                break
        lines += ['', '[Replay input](replay.json) · [JUnit draft](' + test_path(behavior).name + ') · '
                  '[Proposed contract details](contract-draft.json) · [Review record](review.md)', '',
                  'The result is reduced, not globally minimal. The test enforces the expectation; '
                  'review it before adoption.', '', '## Replay and adoption', '', '```sh',
                  './scripts/undertow explore --replay ' + shlex.quote(str(output / 'replay.json'))
                  + ' --java-home "$JAVA_HOME" --output .undertow/replayed-example', '',
                  './scripts/undertow explore --adopt ' + shlex.quote(str(output))
                  + ' --subject /path/to/owned-component --output .undertow/adoption', '```', '',
                  'Replay uses the stored subject snapshot. Adoption copies one reviewed draft into '
                  'the selected component without overwriting a test or changing contract policy.', '']
    elif report['outcome'] == 'no_counterexample_within_budget':
        lines += ['No counterexample was observed within this run’s budget. This does not prove correctness.', '']
    else:
        lines += ['Execution did not establish a reproducible counterexample. Inspect the problems below.', '']
    problems = report['problems'] + metadata['problems']
    if problems:
        lines += ['## Unfinished work', ''] + ['- ' + problem for problem in problems] + ['']
        if result:
            lines += ['The verified original finding is retained; later work did not finish.', '']
    if metadata.get('example_checks'):
        checks = metadata['example_checks']
        lines += ['## Exported test execution', '',
                  'Candidate: `' + checks['candidate']['status'] + '`. Correction: `'
                  + checks['correction']['status'] + '`.', '']
    lines += ['## Scope and execution', '', report['assumptions'], '',
              f"Seed: {report['seed']}. Generated scenarios completed: {report['counts'].get('search_completed', 0)}.",
              'Termination: `' + report.get('termination', 'unfinished') + '`.', '',
              '[Full observations, budgets and identities](report.json) · [Raw execution report](execution.md)', '',
              'Local sequential execution of owned, reviewed fixtures. No trusted CI provenance or mathematical proof. '
              'Maven and Java run as the host user; this command is not an untrusted-code sandbox.', '']
    (output / 'report.md').write_text('\n'.join(lines))
    titles = {'counterexample_found': 'Counterexample reproduced',
              'no_counterexample_within_budget': 'No counterexample within budget', 'incomplete': 'Search incomplete'}
    title = 'Undertow: ' + titles[report['outcome']]
    # Plain HTML with escaped text, no JavaScript, external assets or Markdown execution.
    body = '<h1>' + html.escape(title) + '</h1><p><a href="report.md">Readable Markdown</a> · '
    body += '<a href="report.json">Full JSON</a></p>'
    if metadata.get('example'):
        body += '<p><strong>Authored example:</strong> an intentionally defective owned fixture, executed as real Java.</p>'
    if result:
        violation = result['violation']
        body += '<h2>' + html.escape(violation['expectation'].replace('_', ' ')) + '</h2>'
        body += '<p>First violation at step <strong>' + str(violation['step']) + '</strong>.</p>'
        body += '<p><strong>Expected:</strong> ' + html.escape(violation['expected']['status'] + '; ' + state_text(violation['expected'])) + '</p>'
        body += '<p><strong>Observed:</strong> ' + html.escape(violation['observed']['status'] + '; ' + state_text(violation['observed'])) + '</p>'
        body += '<table><thead><tr><th>Step</th><th>Operation</th><th>Result</th><th>Observed state</th></tr></thead><tbody>'
        for index, operation in enumerate(search.case_steps(result['scenario'], behavior), 1):
            after = result['trace'][index]
            values = [str(index), operation_text(operation), after['status'], state_text(after)]
            body += '<tr>' + ''.join('<td>' + html.escape(value) + '</td>' for value in values) + '</tr>'
            if index == violation['step']:
                break
        body += '</tbody></table>'
        body += '<p><a href="replay.json">Replay</a> · <a href="' + test_path(behavior).name + '">JUnit draft</a></p>'
    else:
        body += '<p>' + html.escape(lines[4]) + '</p>'
    body += '<details><summary>Scope, counts, replay and adoption instructions</summary><pre>' + html.escape('\n'.join(lines)) + '</pre></details>'
    (output / 'index.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1"><title>' + html.escape(title)
        + '</title><style>body{max-width:1000px;margin:40px auto;padding:0 20px;font:17px/1.6 system-ui;'
        'color:#172b37;background:#fbfaf7}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}'
        'a{color:#075e88}table{width:100%;border-collapse:collapse;margin:24px 0}th,td{text-align:left;'
        'padding:12px;border-bottom:1px solid #d7dfe2}th{background:#e9f1f4}details{margin-top:30px}'
        'summary{cursor:pointer;font-weight:600}</style>' + body + '</html>')


def adopt(args):
    root = args.adopt.resolve()
    name, behavior, bundle = replay_input(root / 'replay.json', args.behavior)
    report = read_json(root / 'report.json')
    result = selected_finding(report)
    if (report['outcome'] != 'counterexample_found' or result is None
            or result['scenario'] != bundle['scenario']
            or result['violation']['expectation'] != bundle['expectation']):
        raise ValueError('Adoption requires a matching reproducible finding and replay')
    source = root / test_path(behavior).name
    if source.is_symlink() or not source.is_file() or source.stat().st_size > search.MAX_OUTPUT:
        raise ValueError('Expected a regular generated test')
    expected = behavior.junit(bundle['scenario'])
    proposal = read_json(root / 'contract-draft.json')
    if source.read_text() != expected or proposal['test_sha256'] != search.digest(source):
        raise ValueError('Draft test changed; review or regenerate it before adoption')
    subject = args.subject.resolve()
    if not all((subject / path).is_file() for path in search.subject_paths(behavior)):
        raise ValueError('Adoption target does not contain the selected component and Maven descriptor')
    target = subject / test_path(behavior)
    for parent in [target, *target.parents]:
        if parent == subject:
            break
        if parent.is_symlink():
            raise ValueError('Adoption target must not traverse symlinks')
    if target.exists():
        raise ValueError('Test already exists; adoption never overwrites source')
    args.output.mkdir(parents=True, exist_ok=False)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('x') as destination:
        destination.write(expected)
    receipt = dict(kind='local-draft-adoption', status='draft_adopted', test_source=str(test_path(behavior)),
                   test_sha256=search.digest(target), expectation=bundle['expectation'],
                   policy_changed=False, trusted_ci_provenance=False, owner_approval='not_recorded')
    search.write_json(args.output / 'adoption.json', receipt)
    print('Draft adopted: ' + str(target))
    print('Run the component test suite, then use the existing owner-approved contract workflow if desired.')
    return 0


def run(args):
    name = args.behavior or args.example or 'refund-cap'
    behavior = PROFILES[name]
    replay, bundle, fixture = None, None, None
    if args.replay:
        name, behavior, bundle = replay_input(args.replay, args.behavior)
        replay = bundle['scenario']
        subject = args.subject or args.replay.resolve().parent / 'subject'
    else:
        subject = args.subject
    output = args.output.resolve()
    with tempfile.TemporaryDirectory(prefix='undertow-explore-') as temporary:
        if args.example:
            subject, fixture = example_input(name, Path(temporary))
        if bundle:
            actual = search.identity({str(path): subject / path for path in search.subject_paths(behavior)})
            if actual != bundle['subject']:
                raise ValueError('Replay subject changed; use its exact stored snapshot')
        report = search.run(subject, output, args.java_home.resolve(), seed=args.seed,
                            scenarios=args.scenarios, max_length=args.max_length, runtime=args.runtime,
                            replay=replay, replay_expectation=bundle['expectation'] if bundle else None,
                            behavior=behavior)
        metadata = dict(kind='local-explore', behavior=name, example=args.example, problems=[],
                        independent_review='pending', trusted_ci_provenance=False)
        draft_metadata(output, name, behavior, report)
        if args.example and report['finding'] and not report['problems']:
            try:
                expectation = report['finding']['expectation']
                draft = output / test_path(behavior).name
                candidate = search.run_junit(output / 'subject', draft, output, args.java_home.resolve(), behavior, expectation)
                correction_output = output / 'correction'
                correction_output.mkdir()
                corrected, _ = search.prepare(fixture, correction_output, search.environment(args.java_home.resolve()), 120, behavior)
                correction = search.run_junit(corrected, draft, correction_output, args.java_home.resolve(), behavior, expectation)
                metadata['example_checks'] = dict(candidate=candidate, correction=correction)
                if candidate['status'] == 'passed' or correction['status'] != 'passed':
                    raise search.ExecutionProblem('Example draft did not distinguish the defect and correction')
            except (search.ExecutionProblem, OSError, ValueError) as error:
                metadata['problems'].append(str(error))
        (output / 'report.md').rename(output / 'execution.md')
        render(output, name, behavior, report, metadata)
        search.write_json(output / 'explore.json', metadata)
    print(report['outcome'] + ': ' + str(output / 'report.md'))
    print('HTML report: ' + str(output / 'index.html'))
    if report['problems'] or metadata['problems'] or report['outcome'] == 'incomplete':
        return 2
    return 1 if report['outcome'] == 'counterexample_found' else 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog='undertow explore', description='Find and replay bounded behavior failures in an explicitly selected owned Java component.')
    parser.add_argument('--subject', type=Path, help='Owned Maven component matching the selected reviewed adapter')
    parser.add_argument('--behavior', choices=list(PROFILES))
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--example', choices=list(PROFILES), help='Run an intentionally defective bundled example')
    modes.add_argument('--replay', type=Path, help='Replay input from a previous explore run')
    modes.add_argument('--adopt', type=Path, help='Explicitly copy a reviewed run’s draft into --subject; never overwrite')
    parser.add_argument('--java-home', type=Path, help='JDK 21+ for the selected component')
    parser.add_argument('--output', type=Path, required=True, help='New local artifact directory')
    parser.add_argument('--seed', type=int, default=17)
    parser.add_argument('--scenarios', type=int, default=100)
    parser.add_argument('--max-length', type=int, default=12)
    parser.add_argument('--runtime', type=float, default=30)
    args = parser.parse_args(argv)
    if args.example and (args.subject or args.behavior and args.behavior != args.example):
        parser.error('--example selects its own subject and matching behavior')
    if args.adopt and not args.subject:
        parser.error('--adopt requires --subject')
    if not args.adopt and args.java_home is None:
        parser.error('--java-home is required for execution')
    if not (args.adopt or args.example or args.replay or args.subject):
        parser.error('Select --example, --subject, --replay, or --adopt')
    try:
        return adopt(args) if args.adopt else run(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print('explore: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
