import {SCENARIOS, runDraft, parsePrUrl, validateMetadata, currentSnapshotState} from './model.mjs';

const $ = id => document.getElementById(id);
let scenario = 'preview';

function options(id, entries, selected) {
  $(id).replaceChildren(...entries.map(([value,label]) => {
    const option = document.createElement('option');
    option.value = value; option.textContent = label;
    return option;
  }));
  $(id).value = selected;
}

function invalidate(changed = true) {
  $('outcome').hidden = true;
  $('ready').hidden = false;
  $('result').dataset.state = 'ready';
  $('ready').querySelector('.result-title').textContent = changed ? 'Run again' : 'Ready to run';
  $('ready-hint').textContent = changed ? 'Inputs changed. Run to see the result.' : 'Run the action against your rule.';
  $('rule-code').textContent = scenario === 'preview'
    ? `ticket.owner === "${$('expectation').value}"`
    : `accept("alex") === ${$('expectation').value === 'blocked' ? 'denied' : 'allowed'}`;
}

function selectScenario(name) {
  scenario = name;
  const spec = SCENARIOS[name];
  $('scenario-description').textContent = spec.description;
  $('expectation-label').textContent = spec.ruleLabel;
  $('action-label').textContent = spec.actionLabel;
  options('expectation',spec.expectations,spec.defaultExpectation);
  options('action',spec.actions,spec.defaultAction);
  document.querySelectorAll('[data-scenario]').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.scenario === name)));
  invalidate(false);
}

$('experiment').addEventListener('submit', event => {
  event.preventDefault();
  const result = runDraft({scenario,expectation:$('expectation').value,action:$('action').value});
  $('ready').hidden = true;
  $('outcome').hidden = false;
  $('result').dataset.state = result.passed ? 'passed' : 'failed';
  $('result-label').textContent = result.passed ? 'RULE HOLDS' : 'RULE BROKEN';
  $('owner-before').textContent = result.before;
  $('owner-after').textContent = result.after;
  $('expected').textContent = result.expected;
  $('observed').textContent = result.observed;
  $('result-message').textContent = result.message;
});
for (const id of ['expectation','action']) $(id).addEventListener('change', () => invalidate());
$('break-action').addEventListener('click', () => {
  const expectation = $('expectation').value;
  $('action').value = SCENARIOS[scenario].actions.find(([action]) => !runDraft({scenario,expectation,action}).passed)[0];
  invalidate();
});
$('reset').addEventListener('click', () => selectScenario(scenario));
document.querySelectorAll('[data-scenario]').forEach(button => button.addEventListener('click', () => selectScenario(button.dataset.scenario)));
selectScenario(scenario);

// Archived CI evidence is independent of editable sandbox state.
let catalog, evidencePromise, metadataRequest, prRevision = 0;
const evidence = new Map();
const expectedStates = {broken:'unsatisfied',fixed:'satisfied',missing:'incomplete'};
const summaries = {
  broken:'Broken PR: the approved preview check failed.',
  fixed:'Fixed PR: both approved checks passed.',
  missing:'No CI evidence supplied: both checks are incomplete.'
};
function link(id, href, label) {
  $(id).href = href;
  if (label !== undefined) $(id).textContent = label;
}
function selectCase(name) {
  const report = evidence.get(name), item = catalog.cases[name];
  const root = `https://github.com/${catalog.repository}`;
  document.querySelectorAll('[data-case]').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.case === name)));
  $('evidence-summary').textContent = summaries[name];
  $('contract-results').replaceChildren(...report.contracts.map(contract => {
    const row = document.createElement('div'); row.className = 'contract';
    const id = document.createElement('span'); id.textContent = contract.id;
    const status = document.createElement('span'); status.className = 'status'; status.dataset.state = contract.status; status.textContent = contract.status;
    row.append(id,status); return row;
  }));
  link('pr-link',item.pr,`Public PR #${item.number} ↗`);
  link('report-link',`evidence/${name}/contracts.json`);
  link('policy-link',`${root}/blob/${catalog.policyCommit}/.undertow/contracts.yaml`,catalog.policyCommit);
  $('head-sha').textContent = item.head;
  $('execution').replaceChildren();
  if (item.run) {
    const run = document.createElement('a'); run.href = `${root}/actions/runs/${item.run}`; run.target = '_blank'; run.rel = 'noreferrer'; run.textContent = `${item.run} ↗`; $('execution').append(run);
    link('bundle-link',`evidence/${name}/ci-bundle.json`);
  } else $('execution').textContent = 'No execution imported';
  $('bundle-link').hidden = !item.run;
  $('snapshot-note').textContent = `Captured ${catalog.capturedAt.slice(0,10)}. Archived import; not revalidated by this page. Release not evaluated.`;
}
async function loadEvidence() {
  if (!evidencePromise) evidencePromise = (async () => {
    const response = await fetch('evidence/catalog.json');
    if (!response.ok) throw new Error('Evidence catalog unavailable.');
    catalog = await response.json();
    for (const name of ['broken','fixed','missing']) {
      const response = await fetch(`evidence/${name}/contracts.json`);
      if (!response.ok) throw new Error('Archived report unavailable.');
      const report = await response.json();
      if (report.verification !== expectedStates[name] || report.head_sha !== catalog.cases[name].head || report.policy?.policy_commit !== catalog.policyCommit || report.release_decision !== 'not_evaluated') throw new Error('Archived subject mismatch.');
      evidence.set(name,report);
    }
    selectCase('broken');
  })().catch(error => {
    evidence.clear();
    evidencePromise = undefined;
    $('evidence-summary').textContent = `Evidence unavailable: ${error.message}`;
    throw error;
  });
  return evidencePromise;
}
$('real-evidence').addEventListener('toggle', () => {
  if ($('real-evidence').open) loadEvidence().catch(() => {});
});
document.querySelectorAll('[data-case]').forEach(button => button.addEventListener('click', () => {
  loadEvidence().then(() => selectCase(button.dataset.case)).catch(() => {});
}));

function showStatus(message, url) {
  $('pr-status').replaceChildren(document.createTextNode(message));
  if (url) {const anchor = document.createElement('a'); anchor.href = url; anchor.target = '_blank'; anchor.rel = 'noreferrer'; anchor.textContent = 'Open PR ↗'; $('pr-status').append(anchor);}
}
$('pr-url').addEventListener('input', () => {
  prRevision++;
  metadataRequest?.abort(); metadataRequest = undefined; $('pr-status').replaceChildren();
});
$('pr-form').addEventListener('submit', async event => {
  event.preventDefault();
  const revision = ++prRevision;
  metadataRequest?.abort(); metadataRequest = undefined;
  let requested;
  try {requested = parsePrUrl($('pr-url').value);} catch(error) {showStatus(error.message); return;}
  const name = requested.repository.toLowerCase() === 'alpercandemir/undertow-showcase' ? ({'1':'broken','2':'fixed'})[requested.number] : undefined;
  if (name) {
    try {await loadEvidence();} catch(error) {if (revision === prRevision) showStatus(error.message); return;}
    if (revision !== prRevision) return;
    selectCase(name); $('real-evidence').open = true;
    showStatus('Archived result loaded above.');
    const check = document.createElement('button'); check.type = 'button'; check.className = 'text-button'; check.textContent = 'Check current GitHub head ↗';
    check.addEventListener('click', () => fetchMetadata(requested,catalog.cases[name])); $('pr-status').append(document.createElement('br'),check);
  } else await fetchMetadata(requested);
});
async function fetchMetadata(requested, snapshot) {
  metadataRequest?.abort();
  const controller = new AbortController(); metadataRequest = controller;
  const timeout = setTimeout(() => controller.abort(),8000);
  showStatus('Reading GitHub metadata…');
  try {
    const response = await fetch(requested.api,{signal:controller.signal,headers:{Accept:'application/vnd.github+json'},credentials:'omit',redirect:'error',referrerPolicy:'no-referrer'});
    if (!response.ok) throw new Error([403,429].includes(response.status) ? 'GitHub access or rate limit reached. No result produced.' : `GitHub returned HTTP ${response.status}.`);
    const data = validateMetadata(await response.json(),requested);
    if (metadataRequest !== controller) return;
    showStatus(snapshot
      ? currentSnapshotState(snapshot,data) === 'same_subject' ? 'Head and target match the archive. CI evidence was not refreshed.' : 'STALE SNAPSHOT: head or target moved. The archive does not assess the current PR.'
      : `${data.title} · NOT EVALUATED. Metadata only; no approved policy or imported evidence for this PR.`,requested.url);
  } catch(error) {
    if (metadataRequest === controller) showStatus(error.name === 'AbortError' ? 'GitHub request timed out. No result produced.' : error.message);
  } finally {clearTimeout(timeout);}
}
