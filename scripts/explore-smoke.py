#!/usr/bin/env python3
"""Exercise the public source/JAR adoption path using only owned fixture copies."""

import argparse
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'experiments/behavior-search'))
import explore
import search
sys.path.pop(0)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--harness-java-home', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = search.environment(args.java_home.resolve())
    prefix = [str(ROOT / 'scripts/undertow'), 'explore']
    if args.jar:
        if not args.harness_java_home:
            parser.error('--jar requires --harness-java-home (JDK 25+)')
        prefix = [str(args.harness_java_home.resolve() / 'bin/java'), '-jar', str(args.jar.resolve()), 'explore']
    runs = []

    def execute(arguments, expected, label):
        code = search.command([*prefix, *map(str, arguments)], env, 600, output / (label + '.log'))
        require(code == expected, f'{label}: expected exit {expected}, observed {code}; inspect its log')
        runs.append(dict(step=label, exit_code=code))

    for name, behavior in explore.PROFILES.items():
        found = output / (name + '-example')
        execute(['--example', name, '--java-home', args.java_home.resolve(), '--output', found], 1, name + '-example')
        report = json.loads((found / 'report.json').read_text())
        metadata = json.loads((found / 'explore.json').read_text())
        require(report['outcome'] == 'counterexample_found' and not report['problems'] and not metadata['problems'], 'Example did not finish')
        checks = metadata['example_checks']
        require(checks['candidate']['status'] != 'passed' and checks['correction']['status'] == 'passed', 'Paired example export failed')
        require(checks['candidate']['test_sha256'] == checks['correction']['test_sha256'], 'Different tests used for the pair')
        replayed = output / (name + '-replay')
        execute(['--replay', found / 'replay.json', '--java-home', args.java_home.resolve(), '--output', replayed], 1, name + '-replay')
        before = search.digest(found / 'report.json')
        execute(['--example', name, '--java-home', args.java_home.resolve(), '--output', found], 2, name + '-existing-output')
        require(search.digest(found / 'report.json') == before, 'Existing artifacts were overwritten')
        owned = output / (name + '-owned-component')
        fixture = ROOT / 'demo' / ('ticket-transfer' if name == 'ticket-transfer' else 'refund-extraction')
        for path in search.subject_paths(behavior):
            target = owned / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((fixture / path).read_bytes())
        shutil.copytree(fixture / 'src/test', owned / 'src/test')
        adoption = output / (name + '-adoption')
        execute(['--adopt', found, '--subject', owned, '--output', adoption], 0, name + '-adoption')
        require(not (owned / '.undertow/contracts.yaml').exists(), 'Adoption changed contract policy')
        target = owned / explore.test_path(behavior)
        digest = search.digest(target)
        execute(['--adopt', found, '--subject', owned, '--output', output / (name + '-duplicate-adoption')], 2, name + '-existing-test')
        require(search.digest(target) == digest, 'Existing test was overwritten')
        code = search.command(['mvn', '--offline', '--batch-mode', '--no-transfer-progress', '-f', str(owned / 'pom.xml'), 'test'],
                              env, 120, output / (name + '-adopted-suite.log'))
        require(code == 0, 'Adopted draft failed on the corrected component')
        proposal = json.loads((found / 'contract-draft.json').read_text())
        cases = []
        for path in (owned / 'target/surefire-reports').glob('TEST-*.xml'):
            cases += list(ET.parse(path).getroot().iter('testcase'))
        selected = [case for case in cases if case.get('classname', '') + '.' + case.get('name', '') == proposal['test_id']]
        require(len(selected) == 1 and all(selected[0].find(tag) is None for tag in ['error', 'failure', 'skipped']), 'Adopted test did not actually execute and pass')
        control = output / (name + '-corrected-search')
        execute(['--subject', owned, '--behavior', name, '--java-home', args.java_home.resolve(), '--output', control], 0, name + '-corrected-search')
        correct = json.loads((control / 'report.json').read_text())
        require(correct['outcome'] == 'no_counterexample_within_budget' and correct['finding'] is None, 'Correction produced a finding')
        require(correct['counts']['search_completed'] == 100, 'Corrected search stopped early')
        print(name + ': example, replay, adoption, real test execution and bounded correction passed', flush=True)
    broken = output / 'uncompilable-component'
    main = broken / search.MAIN
    main.parent.mkdir(parents=True)
    main.write_text('this cannot compile\n')
    (broken / 'pom.xml').write_bytes((ROOT / 'demo/refund-extraction/pom.xml').read_bytes())
    incomplete = output / 'incomplete-search'
    execute(['--subject', broken, '--behavior', 'refund-cap', '--java-home', args.java_home.resolve(), '--output', incomplete], 2, 'uncompilable')
    report = json.loads((incomplete / 'report.json').read_text())
    require(report['outcome'] == 'incomplete' and report['finding'] is None, 'Build failure became a discovery')
    search.write_json(output / 'smoke.json', dict(kind='owned-public-explore-smoke', trusted_ci_provenance=False,
                                               mode='jar' if args.jar else 'checkout', steps=runs,
                                               independent_adoption=False))
    print('Public explore path passed; this authored exercise does not establish independent adoption.')


if __name__ == '__main__':
    main()
