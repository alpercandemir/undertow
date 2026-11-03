export const APPROVED_EXPECTATION = 'Opening a ticket-transfer preview leaves ownership unchanged and the invitation usable by the named recipient.';
export const PREVIEW_TEST = 'example.tickets.TransferContractTest.linkPreviewDoesNotAcceptTransfer';

export function parsePrUrl(value) {
  if (typeof value !== 'string' || value.length > 300) throw new Error('Use a canonical public GitHub PR URL.');
  const match = /^https:\/\/github\.com\/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))\/([A-Za-z0-9_.-]{1,100})\/pull\/([1-9][0-9]{0,9})\/?$/i.exec(value.trim());
  if (!match || ['.', '..'].includes(match[2])) throw new Error('Use https://github.com/owner/repo/pull/123, without query parameters or a fragment.');
  const [, owner, repo, number] = match;
  return {owner, repo, number, repository: `${owner}/${repo}`, url: `https://github.com/${owner}/${repo}/pull/${number}`, api: `https://api.github.com/repos/${owner}/${repo}/pulls/${number}`};
}

export function proposalEffect(expectation, binding) {
  if (!expectation.trim()) throw new Error('Describe the intended behavior.');
  if (expectation === APPROVED_EXPECTATION && binding === 'preview') return {changed:false, status:'unchanged', message:'This matches the approved rule. The archived CI result above still applies only to its pinned candidate.'};
  return {changed:true, status:'incomplete', message: binding === 'preview'
    ? 'PROPOSAL · Owner review required. Keeping the same test does not establish that it checks your new intent. Approve a policy binding and obtain matching evidence before verification.'
    : 'PROPOSAL · Evidence missing. No approved execution is bound to the preview-title check. The existing ownership test cannot answer this requirement.'};
}

export function validateMetadata(data, requested) {
  if (!data || data.base?.repo?.private !== false || data.base?.repo?.full_name?.toLowerCase() !== requested.repository.toLowerCase()
      || data.number !== Number(requested.number) || !/^[0-9a-f]{40}$/.test(data.head?.sha ?? '') || !/^[0-9a-f]{40}$/.test(data.base?.sha ?? '')
      || typeof data.title !== 'string' || !['open','closed'].includes(data.state)) throw new Error('GitHub returned an unexpected repository or PR identity.');
  return {title:data.title.slice(0,300), head:data.head.sha, base:data.base.sha, state:data.state};
}

export function currentSnapshotState(snapshot, metadata) {
  return snapshot.head === metadata.head && snapshot.base === metadata.base ? 'same_subject' : 'stale';
}

// This is an illustrative local business model, never a JUnit evidence producer.
export function simulateFlow({preview, acceptance, visitor}) {
  if (!['read','accept'].includes(preview) || !['recipient','anyone'].includes(acceptance) || !['preview','leo','alex'].includes(visitor)) throw new Error('Unsupported local flow.');
  let owner = 'mira', accepted = false, denied = false;
  const accept = (account) => {
    if (acceptance === 'recipient' && account !== 'leo') { denied = true; return; }
    owner = account;
    accepted = true;
  };
  if (visitor === 'preview' && preview === 'accept') accept('leo');
  else if (visitor !== 'preview') accept(visitor);
  return {owner, accepted, denied, previewPreserved: preview === 'read', recipientBound: acceptance === 'recipient'};
}
