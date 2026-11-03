import {APPROVED_EXPECTATION, parsePrUrl, proposalEffect, validateMetadata, currentSnapshotState, simulateFlow} from './model.mjs';

const $ = id => document.getElementById(id);
const evidence = new Map();
let catalog, activeCase = 'broken', metadataRequest;
const stories = {
  broken: {state:'unsatisfied', label:'UNSATISFIED / APPROVED ASSERTION FAILED', title:'The preview took the ticket.', description:'The link preview accepted the invitation before Leo did. The ownership assertion failed; the account restriction still passed.', owner:'Leo owns it', caption:'Archived case: the preview changes ownership and consumes the invitation. The illustration describes the fixture; the result comes from JUnit.', diff:'− return new Preview("NIGHT / SHIFT", "Transfer invitation");\n+ accept(recipient);\n+ return new Preview("NIGHT / SHIFT", "Your ticket is ready");'},
  fixed: {state:'satisfied', label:'SATISFIED / TWO DECLARED CHECKS PASSED', title:'The invitation stays an invitation.', description:'The preview renders copy without accepting the transfer. Both unchanged, approved assertions passed at this exact head.', owner:'Mira still owns it', caption:'Archived case: a preview leaves the invitation usable. Leo can explicitly accept later. Real identity and provider behavior are outside these checks.', diff:'− return new Preview("NIGHT / SHIFT", "Transfer invitation");\n+ return new Preview("NIGHT / SHIFT", "A ticket is waiting for you");'},
  missing: {state:'incomplete', label:'INCOMPLETE / NO CI EVIDENCE SUPPLIED', title:'A diff cannot establish a pass.', description:'This is the same candidate as the regression, evaluated without its CI bundle. Undertow records the missing evidence instead of guessing.', owner:'Not established', caption:'Evidence-absence exercise: a real Undertow report with CI evidence deliberately withheld. No test execution is claimed for this assessment.', diff:'+ accept(recipient);\n+ return new Preview("NIGHT / SHIFT", "Your ticket is ready");\n\nNo approved execution evidence supplied.'}
};

function link(id, href, label) {
  const element = $(id);
  element.href = href;
  if (label !== undefined) element.textContent = label;
}

function selectCase(name) {
  if (!evidence.has(name)) return;
  activeCase = name;
  const report = evidence.get(name), item = catalog.cases[name], story = stories[name];
  document.querySelectorAll('[data-case]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.case === name)));
  $('verdict').dataset.state = report.verification;
  $('verdict-label').textContent = story.label;
  $('verdict-title').textContent = story.title;
  $('verdict-description').textContent = story.description;
  $('owner-after').textContent = story.owner;
  $('story-caption').textContent = story.caption;
  $('source-diff').textContent = story.diff;
  const root = `https://github.com/${catalog.repository}`;
  link('source-link', `${root}/blob/${item.head}/src/main/java/example/tickets/TicketTransfer.java`);
  link('pr-link', item.pr, `Open public PR #${item.number} ↗`);
  link('policy-link', `${root}/blob/${catalog.policyCommit}/.undertow/contracts.yaml`, `${catalog.policyCommit.slice(0,12)} · approved baseline ↗`);
  $('head-sha').textContent = item.head;
  link('report-link', `evidence/${name}/contracts.json`);
  $('run-link').hidden = !item.run;
  if (item.run) link('run-link', `${root}/actions/runs/${item.run}`, `${item.run} · ${name === 'broken' ? 'assertion failed' : 'passed'} ↗`);
  // Remove prior no-execution text when switching cases.
  [...$('run-link').parentElement.childNodes].filter(node => node.nodeType === Node.TEXT_NODE).forEach(node => node.remove());
  if (!item.run) $('run-link').after(document.createTextNode('No execution imported'));
  $('bundle-link').hidden = !item.run;
  if (item.run) link('bundle-link', `evidence/${name}/ci-bundle.json`);
  $('snapshot-note').textContent = `Captured ${new Date(catalog.capturedAt).toISOString().slice(0,10)}. ${item.run ? 'GitHub artifact origin and digest were checked by the trusted importer. This page reads the archived report; it does not revalidate GitHub evidence.' : 'Missing evidence is deliberate in this case.'} Release: not evaluated.`;
  $('contract-results').replaceChildren();
  for (const contract of report.contracts) {
    const section = document.createElement('article'); section.className = 'contract';
    const top = document.createElement('div'); top.className = 'contract-top';
    const id = document.createElement('code'); id.textContent = contract.id;
    const status = document.createElement('span'); status.className = 'status'; status.dataset.state = contract.status; status.textContent = contract.status;
    const description = document.createElement('p'); description.textContent = contract.expectation;
    const check = document.createElement('span'); check.className = 'test-id'; check.textContent = contract.checks.map(check => `${check.test_id} · ${check.reported_status ?? 'no execution'}`).join('\n');
    top.append(id,status); section.append(top,description,check); $('contract-results').append(section);
  }
  // A visitor's draft is independent of the selected historical candidate.
}

function renderFlow() {
  const preview = $('preview-flow').value, acceptance = $('accept-flow').value, visitor = $('visitor').value;
  const result = simulateFlow({preview, acceptance, visitor});
  $('flow-result').textContent = `SIMULATION · Owner: ${result.owner.toUpperCase()}. Invitation: ${result.accepted ? 'accepted' : 'still open'}.${result.denied ? ' This account was denied.' : ''} Preview rule: ${result.previewPreserved ? 'preserved by this flow' : 'violated by this flow'}. Recipient rule: ${result.recipientBound ? 'enforced by this flow' : 'not enforced by this flow'}. This is local model output, not a verified contract result.`;
  $('flow-code').textContent = `preview() {\n${preview === 'accept' ? '  accept("leo"); // accepts even for a link scanner\n' : ''}  return invitation;\n}\n\naccept(account) {\n${acceptance === 'recipient' ? '  require(account == "leo");\n' : '  // anyone with the link may accept\n'}  ticket.owner = account;\n  invitation.accepted = true;\n}`;
}

$('flow-form').addEventListener('submit', event => {event.preventDefault(); renderFlow();});
for (const id of ['preview-flow','accept-flow','visitor']) $(id).addEventListener('change', renderFlow);
renderFlow();
$('expectation').value = APPROVED_EXPECTATION;
function renderProposal() {
  const effect = proposalEffect($('expectation').value, $('test-binding').value);
  $('proposal-result').dataset.proposal = String(effect.changed);
  $('proposal-result').textContent = effect.message;
}
$('rule-form').addEventListener('submit', event => {event.preventDefault(); renderProposal();});
$('expectation').addEventListener('input', () => {if ($('expectation').value.trim()) renderProposal(); else {$('proposal-result').textContent = 'Draft is empty. Describe the behavior before proposing a contract.'; $('proposal-result').dataset.proposal = 'true';}});
$('test-binding').addEventListener('change', renderProposal);
$('reset-rule').addEventListener('click', () => {$('expectation').value = APPROVED_EXPECTATION; $('test-binding').value = 'preview'; renderProposal();});
document.querySelectorAll('[data-case]').forEach(button => button.addEventListener('click', () => selectCase(button.dataset.case)));

function showStatus(message, url) {
  $('pr-status').replaceChildren(document.createTextNode(message));
  if (url) {const anchor = document.createElement('a'); anchor.href = url; anchor.target = '_blank'; anchor.rel = 'noreferrer'; anchor.textContent = 'Open public PR ↗'; $('pr-status').append(anchor);}
}

$('pr-form').addEventListener('submit', async event => {
  event.preventDefault();
  metadataRequest?.abort();
  metadataRequest = undefined;
  let requested;
  try {requested = parsePrUrl($('pr-url').value);} catch(error) {showStatus(error.message); return;}
  const known = Object.entries(catalog?.cases ?? {}).find(([name,item]) => name !== 'missing' && item.pr.toLowerCase() === requested.url.toLowerCase());
  if (known) {
    selectCase(known[0]);
    showStatus('Loaded the archived, pinned result above. Check current GitHub metadata to see whether the PR head or target has moved.');
    const check = document.createElement('button'); check.type = 'button'; check.className = 'text-button'; check.textContent = 'Check current GitHub head ↗';
    check.addEventListener('click', () => fetchMetadata(requested, known[1])); $('pr-status').append(document.createElement('br'), check);
    return;
  }
  await fetchMetadata(requested);
});

async function fetchMetadata(requested, snapshot) {
  metadataRequest?.abort();
  const controller = new AbortController(); metadataRequest = controller;
  const timeout = setTimeout(() => controller.abort(), 8000);
  showStatus('Reading public PR metadata from GitHub…');
  try {
    const response = await fetch(requested.api, {signal:controller.signal, headers:{Accept:'application/vnd.github+json'}, credentials:'omit', redirect:'error', referrerPolicy:'no-referrer'});
    if (!response.ok) throw new Error([403,429].includes(response.status) ? 'GitHub declined this request or reached a rate limit. Try later; no contract result was produced.' : `GitHub returned HTTP ${response.status}. Only accessible public PR metadata is supported.`);
    const data = validateMetadata(await response.json(), requested);
    if (metadataRequest !== controller) return;
    const same = snapshot && currentSnapshotState(snapshot,data) === 'same_subject';
    showStatus(snapshot
      ? `${data.title} · ${same ? 'The PR head and target match the archived subject. This metadata check does not refresh CI evidence.' : 'STALE SNAPSHOT: the PR head or target differs. Do not apply the archived result to the current change.'} Head ${data.head}.`
      : `${data.title} · ${data.state} · head ${data.head}. NOT EVALUATED: this prototype has no approved policy or imported execution evidence for this PR. Public metadata is not verification. The results above still refer to the selected ticket showcase.`, requested.url);
  } catch(error) {
    if (metadataRequest !== controller) return;
    showStatus(error.name === 'AbortError' ? 'GitHub metadata request timed out. No contract result was produced.' : error.message);
  } finally {clearTimeout(timeout);}
}

try {
  const response = await fetch('evidence/catalog.json');
  if (!response.ok) throw new Error('Evidence catalog is unavailable.');
  catalog = await response.json();
  for (const name of ['broken','fixed','missing']) {
    const response = await fetch(`evidence/${name}/contracts.json`);
    if (!response.ok) throw new Error(`The ${name} evidence snapshot is unavailable.`);
    const report = await response.json();
    if (report.verification !== stories[name].state || report.head_sha !== catalog.cases[name].head || report.policy?.policy_commit !== catalog.policyCommit || report.release_decision !== 'not_evaluated') throw new Error('The evidence snapshot does not match its declared subject.');
    evidence.set(name,report);
  }
  selectCase(activeCase);
} catch(error) {
  evidence.clear();
  $('verdict-label').textContent = 'EVIDENCE UNAVAILABLE';
  $('verdict-title').textContent = 'No result can be shown.';
  $('verdict-description').textContent = `${error.message} Serve this folder over localhost; do not open it as a file URL.`;
  document.querySelectorAll('[data-case]').forEach(button => button.disabled = true);
}
