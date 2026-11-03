import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {APPROVED_EXPECTATION, PREVIEW_TEST, SCENARIOS, runDraft, parsePrUrl, simulateFlow, validateMetadata, currentSnapshotState} from '../../demo/pr-workbench/dist/model.mjs';

test('public PR input cannot select another host, credentials, path, or query', () => {
  for (const value of ['http://github.com/a/b/pull/1','https://github.com.evil.test/a/b/pull/1','https://user@github.com/a/b/pull/1','https://github.com:443/a/b/pull/1','https://github.com/a/../pull/1','https://github.com/a/b/pull/1?token=x','https://github.com/a/b/pull/1#x','https://github.com/a/b/pull/0','https://github.com/a/b/pull/1/files','https://github.com/a%2fb/c/pull/1']) assert.throws(() => parsePrUrl(value), value);
  assert.equal(parsePrUrl(' https://github.com/alpercandemir/undertow-showcase/pull/1/ ').api,'https://api.github.com/repos/alpercandemir/undertow-showcase/pulls/1');
});

test('draft rule is evaluated against the action that actually ran', () => {
  assert.equal(runDraft({scenario:'preview',expectation:'mira',action:'read'}).passed,true);
  const broken = runDraft({scenario:'preview',expectation:'mira',action:'accept'});
  assert.equal(broken.passed,false);
  assert.equal(broken.expected,'Owner: Mira'); assert.equal(broken.observed,'Owner: Leo');
  // Changing a sandbox expectation changes only this local comparison.
  assert.equal(runDraft({scenario:'preview',expectation:'leo',action:'accept'}).passed,true);
  assert.equal(runDraft({scenario:'preview',expectation:'leo',action:'read'}).passed,false);
  assert.equal(runDraft({scenario:'recipient',expectation:'blocked',action:'recipient'}).passed,true);
  const unauthorized = runDraft({scenario:'recipient',expectation:'blocked',action:'anyone'});
  assert.equal(unauthorized.passed,false); assert.equal(unauthorized.after,'Alex');
  assert.equal(runDraft({scenario:'recipient',expectation:'allowed',action:'anyone'}).passed,true);
  for (const [scenario,spec] of Object.entries(SCENARIOS)) {
    for (const [expectation] of spec.expectations) {
      assert.equal(spec.actions.filter(([action]) => runDraft({scenario,expectation,action}).passed).length,1);
    }
  }
  assert.throws(() => runDraft({scenario:'preview',expectation:'anyone',action:'read'}));
  assert.throws(() => runDraft({scenario:'preview',expectation:'mira',action:'execute'}));
  assert.throws(() => runDraft({scenario:'toString',expectation:'mira',action:'read'}));
});

test('local business model exposes premature acceptance and recipient bypass', () => {
  const scanner = simulateFlow({preview:'read',acceptance:'recipient',visitor:'preview'});
  assert.equal(scanner.owner,'mira'); assert.equal(scanner.accepted,false);
  const broken = simulateFlow({preview:'accept',acceptance:'recipient',visitor:'preview'});
  assert.equal(broken.owner,'leo'); assert.equal(broken.accepted,true);
  const denied = simulateFlow({preview:'read',acceptance:'recipient',visitor:'alex'});
  assert.equal(denied.denied,true); assert.equal(denied.owner,'mira');
  const bypass = simulateFlow({preview:'read',acceptance:'anyone',visitor:'alex'});
  assert.equal(bypass.owner,'alex'); assert.equal(bypass.accepted,true);
  assert.equal(simulateFlow({preview:'read',acceptance:'recipient',visitor:'leo'}).owner,'leo');
  assert.throws(() => simulateFlow({preview:'execute',acceptance:'anyone',visitor:'alex'}));
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
    assert.equal(result.contracts[0].expectation,APPROVED_EXPECTATION);
    assert.equal(result.contracts[0].checks[0].test_id,PREVIEW_TEST);
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
