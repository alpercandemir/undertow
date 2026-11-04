'use strict';
const $ = id => document.getElementById(id);
let csrf = '', tenant = '', currentReview = '';
const status = text => { $('status').textContent = text; };
async function api(path, method = 'GET', body, key) {
  const headers = { 'X-Undertow-Tenant': tenant };
  if (method !== 'GET') { headers['Content-Type'] = 'application/json'; headers['X-CSRF-Token'] = csrf; }
  if (key) headers['Idempotency-Key'] = key;
  const result = await fetch(path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
  const value = await result.json();
  if (!result.ok) throw new Error(value.error || 'Request failed');
  return value;
}
const display = (id, value) => { $(id).textContent = JSON.stringify(value, null, 2); };
function task(fn) { return async event => { event?.preventDefault(); try { status('Working…'); await fn(event); status('Updated.'); } catch (error) { status(error.message); } }; }
async function session() {
  const value = await api('/v1/session'); csrf = value.csrf;
  $('workspace').hidden = false; $('logout').hidden = false; $('tenant').replaceChildren();
  for (const item of value.tenants) { const option = document.createElement('option'); option.value = item.id; option.textContent = `${item.id} (${item.roles.join(', ')})`; $('tenant').append(option); }
  const requested = new URLSearchParams(location.search).get('tenant');
  tenant = value.tenants.some(t => t.id === requested) ? requested : (value.tenants[0]?.id || ''); $('tenant').value = tenant;
  if (tenant) await repositories();
}
async function repositories() {
  $('repositories').replaceChildren();
  const values = await api('/v1/repositories');
  for (const repo of values) {
    const article = document.createElement('article'), title = document.createElement('h3'); title.textContent = `${repo.name} · ${repo.enabled ? 'enabled' : 'disabled'}`; article.append(title);
    const settings = document.createElement('textarea'); settings.value = JSON.stringify({enabled: repo.enabled, consent: false, policySource: repo.policySource, policy: repo.policy, monthlyLimit: repo.monthlyLimit, reviewLimit: repo.reviewLimit, branches: repo.branches}, null, 2); settings.rows = 9; settings.setAttribute('aria-label', 'Repository settings'); article.append(settings);
    const save = document.createElement('button'); save.textContent = 'Save settings'; save.onclick = task(async () => { await api(`/v1/repositories/${repo.id}/settings`, 'PATCH', JSON.parse(settings.value)); await repositories(); }); article.append(save);
    const number = document.createElement('input'); number.type = 'number'; number.min = '1'; number.placeholder = 'PR number'; number.setAttribute('aria-label', 'Pull request number'); article.append(number);
    const review = document.createElement('button'); review.textContent = 'Request review'; review.onclick = task(async () => { const key = crypto.randomUUID(); const result = await api(`/v1/repositories/${repo.id}/pull-requests/${Number(number.value)}/reviews`, 'POST', {}, key); currentReview = result.id; display('review', result); $('evidence').hidden = false; }); article.append(review);
    const history = document.createElement('button'); history.textContent = 'Review history'; history.onclick = task(async () => display('review', await api(`/v1/repositories/${repo.id}/reviews`))); article.append(history); $('repositories').append(article);
  }
}
$('tenant').onchange = task(async () => { tenant = $('tenant').value; $('review').textContent = ''; $('artifact').textContent = ''; $('evidence').hidden = true; $('feedback').hidden = true; await repositories(); });
$('connect').onsubmit = task(async event => { const data = new FormData(event.target); await api('/v1/installations', 'POST', {installationId: Number(data.get('installationId'))}); await session(); });
$('register').onsubmit = task(async event => { const data = new FormData(event.target); await api('/v1/repositories', 'POST', {repositoryId: Number(data.get('repositoryId'))}); await repositories(); });
$('usage-refresh').onclick = task(async () => { display('credits', await api('/v1/credits')); display('usage', await api('/v1/usage')); });
$('package').onsubmit = task(async event => { const files = {}; for (const file of new FormData(event.target).getAll('files')) { if (file.size > 256000) throw new Error('Policy file too large'); files[`.undertow/${file.name}`] = await file.text(); } display('package-result', await api('/v1/rule-packages', 'POST', {files})); });
$('detail').onsubmit = task(async event => { currentReview = new FormData(event.target).get('review'); display('review', await api(`/v1/reviews/${encodeURIComponent(currentReview)}`)); $('evidence').hidden = false; });
$('evidence').onclick = task(async () => { const value = await api(`/v1/reviews/${encodeURIComponent(currentReview)}/artifacts`); display('artifact', value.files); $('feedback').hidden = false; });
$('feedback').onsubmit = task(async event => { await api(`/v1/reviews/${encodeURIComponent(currentReview)}/feedback`, 'POST', Object.fromEntries(new FormData(event.target))); });
$('logout').onclick = task(async () => { await api('/auth/logout', 'POST', {}); location.replace('/'); });
session().then(async () => { const review = new URLSearchParams(location.search).get('review'); if (review && tenant) { currentReview = review; display('review', await api(`/v1/reviews/${encodeURIComponent(review)}`)); $('evidence').hidden = false; } }).catch(error => status(error.message));
