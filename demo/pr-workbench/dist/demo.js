import {PRESETS, evaluateTrace, encodeScenario, decodeScenario} from './demo-state.mjs';
import {testStatus} from './refund.mjs';
const $ = id => document.getElementById(id);
let subject = 'broken', preset = 'overflow', fixture;
const format = number => new Intl.NumberFormat('en-US').format(number);
const traceText = trace => `{\n  "id": ${JSON.stringify(trace.id)},\n  "captured_minor": ${trace.captured_minor},\n  "operations": [\n${trace.operations.map(op => '    '+JSON.stringify(op)).join(',\n')}\n  ]\n}`;
function dirty() {
  $('outcome').hidden = true; $('pending').hidden = false;
  $('result').dataset.state = 'pending';
  $('input-status').textContent = '';
  $('share-box').hidden = true;
}
function source() {
  $('guard').textContent = fixture ? fixture.variants[subject].source.split('\n').filter(line => line.includes('if (amount')).map(line => line.trim().split(' throw ')[0]).join('\n') : 'Source capture unavailable; browser replay remains available.';
  document.querySelectorAll('[data-subject]').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.subject === subject)));
  document.querySelectorAll('a[href^="workspace.html"]').forEach(anchor => anchor.href = `workspace.html?subject=${subject}`);
  if (fixture) renderEvidence();
}
function loadPreset(name) {
  preset = name; $('trace').value = traceText(PRESETS[name]);
  document.querySelectorAll('[data-preset]').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.preset === name)));
  dirty();
}
function evaluate() {
  dirty();
  try {
    const result = evaluateTrace($('trace').value,subject);
    $('pending').hidden = true; $('outcome').hidden = false;
    $('result').dataset.state = result.cap_holds ? 'preserved' : 'violated';
    $('verdict').textContent = result.cap_holds ? 'REFUND-CAP / PRESERVED ON THIS TRACE' : 'REFUND-CAP / VIOLATED';
    $('refunded').textContent = format(result.refunded_minor); $('captured').textContent = format(result.captured_minor);
    $('cap-status').textContent = result.cap_holds ? 'preserved' : 'violated';
    $('partial-status').textContent = result.partial;
    $('effects').textContent = result.effects;
    $('operations').replaceChildren(...result.operations.map(op => {
      const row = document.createElement('tr');
      for (const value of [op.key,format(op.amount_minor),op.status,format(op.total_minor)]) {const td = document.createElement('td'); td.textContent = value; row.append(td);}
      row.children[2].className = `op-${op.status}`; return row;
    }));
    $('explanation').textContent = !result.cap_holds
      ? `Partial refunds can succeed while the standing cap breaks: ${format(result.refunded_minor)} settled against ${format(result.captured_minor)} captured.`
      : result.partial === 'not accepted' ? 'The cap holds, but partial refunds were not accepted. Preserving old behavior and delivering new behavior are separate checks.'
      : 'This trace preserves the cap. Inspect operation outcomes and recorded checks before drawing broader conclusions.';
  } catch(error) {$('input-status').textContent = error.message;}
}
function renderEvidence() {
  const rows = [
    ['Standing cap','example.refunds.ExistingRefundContractTest.rejectsASecondFullRefund'],
    ['Proposed partial refund','example.refunds.PartialRefundContractTest.acceptsPartialRefund'],
    ['Proposed shared budget','example.refunds.PartialRefundContractTest.partialRefundsShareOneBudget']
  ];
  $('evidence-rows').replaceChildren();
  for (const [label,id] of rows) {
    const row = document.createElement('tr');
    for (const [text,state] of [[`${label} · ${id.split('.').pop()}`,null],[testStatus(fixture,'legacy',id),testStatus(fixture,'legacy',id)],[testStatus(fixture,subject,id),testStatus(fixture,subject,id)]]) {const td = document.createElement('td'); td.textContent = text; if (state) td.className = state; row.append(td);}
    $('evidence-rows').append(row);
  }
  const probe = document.createElement('tr');
  for (const value of ['Temporary candidate probe','not run',fixture.probe.runs[subject]?.status ?? 'not run']) {const td = document.createElement('td'); td.textContent = value; td.className = value; probe.append(td);}
  $('evidence-rows').append(probe);
  $('evidence-note').textContent = `Actual recorded local JUnit executions, captured ${fixture.captured_at.slice(0,10)}. These rows use the original standing/proposed fixture checks, independently of edited browser traces. They are not a fresh CI assessment.`;
  $('source-identity').textContent = `Selected Java source sha256: ${fixture.variants[subject].source_sha256}`;
}
$('evaluate').addEventListener('click',evaluate);
$('trace').addEventListener('input',() => {document.querySelectorAll('[data-preset]').forEach(button => button.setAttribute('aria-pressed','false')); dirty();});
$('reset').addEventListener('click',() => {loadPreset(preset); evaluate();});
document.querySelectorAll('[data-preset]').forEach(button => button.addEventListener('click',() => {loadPreset(button.dataset.preset); evaluate();}));
document.querySelectorAll('[data-subject]').forEach(button => button.addEventListener('click',() => {subject = button.dataset.subject; source(); evaluate();}));
$('share').addEventListener('click',() => {
  try {
    const url = new URL(window.location.href); url.search = ''; url.hash = 'trace='+encodeScenario(subject,$('trace').value);
    $('share-url').value = url.href; $('share-box').hidden = false; $('input-status').textContent = '';
    $('share-url').focus(); $('share-url').select();
  } catch(error) {$('input-status').textContent = error.message; $('share-box').hidden = true;}
});
function initialScenario() {
  loadPreset('overflow');
  if (window.location.hash.startsWith('#trace=')) {
    try {
      const state = decodeScenario(window.location.hash.slice(7)); subject = state.subject;
      $('trace').value = traceText(state.trace);
      document.querySelectorAll('[data-preset]').forEach(button => button.setAttribute('aria-pressed','false'));
    } catch(error) {$('input-status').textContent = `Shared scenario unavailable: ${error.message}`; source(); return;}
  }
  source(); evaluate();
}
window.addEventListener('hashchange',initialScenario);
initialScenario();
try {
  const response = await fetch('evidence/refund-fixture.json');
  if (!response.ok) throw new Error('Capture not found.');
  const capture = await response.json();
  if (capture.kind !== 'recorded-local-junit' || capture.trusted_ci_import !== false || !capture.probe) throw new Error('Unexpected evidence kind.');
  fixture = capture; source();
} catch(error) {$('evidence-note').textContent = `Recorded evidence unavailable: ${error.message}`; source();}
