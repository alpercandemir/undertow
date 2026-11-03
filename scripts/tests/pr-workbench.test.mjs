import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {parsePrUrl, validateMetadata, currentSnapshotState} from '../../demo/pr-workbench/dist/model.mjs';
import {parseCases, replay, parseManifest, testStatus, discoveryDraft} from '../../demo/pr-workbench/dist/refund.mjs';

test('public PR input cannot select another host, credentials, path, or query', () => {
  for (const value of ['http://github.com/a/b/pull/1','https://github.com.evil.test/a/b/pull/1','https://user@github.com/a/b/pull/1','https://github.com:443/a/b/pull/1','https://github.com/a/../pull/1','https://github.com/a/b/pull/1?token=x','https://github.com/a/b/pull/1#x','https://github.com/a/b/pull/0','https://github.com/a/b/pull/1/files','https://github.com/a%2fb/c/pull/1']) assert.throws(() => parsePrUrl(value), value);
  assert.equal(parsePrUrl(' https://github.com/alpercandemir/undertow-showcase/pull/1/ ').api,'https://api.github.com/repos/alpercandemir/undertow-showcase/pulls/1');
});

test('standing refund cap survives full refunds, partial refunds, and retries', () => {
  const cases = parseCases(JSON.stringify([
    {id:'partial',captured_minor:10000,operations:[{key:'r1',amount_minor:8000},{key:'r2',amount_minor:3000}]},
    {id:'full',captured_minor:10000,operations:[{key:'r1',amount_minor:10000},{key:'r2',amount_minor:10000}]},
    {id:'retry',captured_minor:10000,operations:[{key:'r1',amount_minor:10000},{key:'r1',amount_minor:10000},{key:'r1',amount_minor:2500}]}
  ]));
  assert.equal(replay(cases[0],'broken').refunded_minor,11000);
  assert.equal(replay(cases[0],'broken').cap_holds,false);
  assert.equal(replay(cases[0],'fixed').refunded_minor,8000);
  assert.equal(replay(cases[0],'legacy').refunded_minor,0);
  assert.equal(replay(cases[1],'legacy').refunded_minor,10000);
  assert.equal(replay(cases[1],'broken').refunded_minor,20000);
  for (const variant of ['legacy','broken','fixed']) {
    const retry = replay(cases[2],variant);
    assert.equal(retry.refunded_minor,10000); assert.equal(retry.effects,1);
    assert.deepEqual(retry.operations.map(op => op.status),['settled','duplicate','rejected']);
  }
  assert.throws(() => replay(cases[0],'execute'));
});

test('replay input rejects malformed or unsafe data without executing source', () => {
  const item = {id:'one',captured_minor:100,operations:[{key:'r',amount_minor:1}]};
  for (const invalid of [[],[item,item],[{...item,captured_minor:1.5}],[{...item,captured_minor:Number.MAX_SAFE_INTEGER}],[{...item,command:'run'}],[{...item,operations:[{key:'r',amount_minor:NaN}]}]]) assert.throws(() => parseCases(JSON.stringify(invalid)));
  const nonpositive = {...item,operations:[{key:'r',amount_minor:-1},{key:'z',amount_minor:0}]};
  assert.equal(replay(nonpositive,'fixed').refunded_minor,0);
});

test('local JUnit observations bind source, unchanged tests, and temporary candidate probes', async () => {
  const fixture = JSON.parse(await readFile(new URL('../../demo/pr-workbench/dist/evidence/refund-fixture.json',import.meta.url)));
  const hash = text => createHash('sha256').update(text).digest('hex');
  assert.equal(fixture.kind,'recorded-local-junit'); assert.equal(fixture.trusted_ci_import,false);
  for (const value of Object.values(fixture.variants)) {
    assert.equal(hash(value.source),value.source_sha256);
    assert.equal(value.tests.length,6);
    assert.ok(value.tests.every(test => ['passed','failed'].includes(test.status)));
  }
  const cap = 'example.refunds.ExistingRefundContractTest.rejectsASecondFullRefund';
  const partial = 'example.refunds.PartialRefundContractTest.acceptsPartialRefund';
  assert.equal(testStatus(fixture,'legacy',cap),'passed');
  assert.equal(testStatus(fixture,'broken',cap),'failed');
  assert.equal(testStatus(fixture,'broken',partial),'passed');
  assert.equal(testStatus(fixture,'fixed',cap),'passed');
  assert.equal(testStatus(fixture,'legacy',partial),'failed');
  assert.equal(testStatus(fixture,'fixed','not.executed'),'incomplete');
  assert.equal(hash(Object.values(fixture.test_sources).join('')),fixture.oracle_sha256);
  assert.equal(hash(fixture.probe.source),fixture.probe.sha256);
  assert.equal(fixture.probe.authority,'candidate');
  assert.equal(fixture.probe.runs.broken.status,'failed'); assert.equal(fixture.probe.runs.fixed.status,'passed');
  parseManifest(fixture.contracts); parseManifest(fixture.proposed);
  for (const candidate of fixture.discovery) {
    assert.equal(candidate.authority,'candidate');
    assert.equal(hash(fixture.test_sources[candidate.source]),candidate.source_sha256);
    assert.match(fixture.test_sources[candidate.source].split('\n')[candidate.line-1],/@DisplayName/);
    assert.equal(discoveryDraft(candidate).contracts[0].tests[0].id,candidate.test_id);
    parseManifest(JSON.stringify(discoveryDraft(candidate)));
  }
  // Changing a draft never rewrites the standing manifest or recorded executions.
  const draft = parseManifest(fixture.contracts); draft.contracts[0].expectation = 'Different proposed intent';
  assert.notEqual(draft.contracts[0].expectation,JSON.parse(fixture.contracts).contracts[0].expectation);
  assert.equal(testStatus(fixture,'broken',cap),'failed');
});

test('metadata is public and bound to the requested repository, number and immutable subjects', () => {
  const requested = parsePrUrl('https://github.com/a/b/pull/1');
  const data = {title:'Title',number:1,state:'open',head:{sha:'a'.repeat(40)},base:{sha:'b'.repeat(40),repo:{full_name:'a/b',private:false}}};
  const metadata = validateMetadata(data,requested);
  assert.equal(currentSnapshotState({head:metadata.head,base:metadata.base},metadata),'same_subject');
  assert.equal(currentSnapshotState({head:'c'.repeat(40),base:metadata.base},metadata),'stale');
  assert.equal(currentSnapshotState({head:metadata.head,base:'c'.repeat(40)},metadata),'stale');
  for (const changed of [{...data,number:2},{...data,base:{...data.base,repo:{full_name:'evil/b',private:false}}},{...data,base:{...data.base,repo:{full_name:'a/b',private:true}}},{...data,head:{sha:'main'}}]) assert.throws(() => validateMetadata(changed,requested));
});

test('archived reports retain exact imported bytes, subjects, rule bindings, and per-test results', async () => {
  const root = new URL('../../demo/pr-workbench/dist/evidence/',import.meta.url);
  const catalog = JSON.parse(await readFile(new URL('catalog.json',root)));
  const policy = JSON.parse(await readFile(new URL('approved-policy.json',root)));
  for (const [label,status] of [['broken','unsatisfied'],['fixed','satisfied'],['missing','incomplete']]) {
    const result = JSON.parse(await readFile(new URL(`${label}/contracts.json`,root)));
    assert.equal(result.verification,status);
    assert.equal(result.head_sha,catalog.cases[label].head);
    assert.equal(result.base_sha,catalog.cases[label].base);
    assert.equal(result.policy.policy_commit,catalog.policyCommit);
    assert.equal(result.policy.oracle_sha256,policy.oracle_sha256);
    assert.equal(result.mode,'shadow'); assert.equal(result.release_decision,'not_evaluated');
    assert.equal(result.contracts[0].expectation,'Opening a ticket-transfer preview leaves ownership unchanged and the invitation usable by the named recipient.');
    assert.equal(result.contracts[0].checks[0].test_id,'example.tickets.TransferContractTest.linkPreviewDoesNotAcceptTransfer');
    if (label === 'missing') {assert.ok(result.evidence_gaps.length > 0);continue;}
    const bytes = await readFile(new URL(`${label}/ci-bundle.json`,root));
    const bundle = JSON.parse(bytes);
    assert.equal(createHash('sha256').update(bytes).digest('hex'),result.evidence_source.bundle_sha256);
    assert.equal(bundle.head_sha,result.head_sha);
    assert.equal(bundle.workflow_id,catalog.workflowId);
    assert.equal(bundle.run_id,catalog.cases[label].run);
    assert.equal(bundle.origin.archive_digest,result.evidence_origin.archive_digest);
    assert.equal(bundle.contract_binding.manifest_sha256,policy.manifest_sha256);
    assert.equal(bundle.run_conclusion,label === 'broken' ? 'failure' : 'success');
    const tests = bundle.artifacts.find(artifact => artifact.kind === 'tests').content;
    assert.equal(tests.length,2);
    assert.equal(result.contracts[1].status,'satisfied');
    assert.equal(result.contracts[0].checks[0].reported_status,label === 'broken' ? 'failed' : 'passed');
  }
});
