export const APPROVED_EXPECTATION = 'Opening a ticket-transfer preview leaves ownership unchanged and the invitation usable by the named recipient.';
export const PREVIEW_TEST = 'example.tickets.TransferContractTest.linkPreviewDoesNotAcceptTransfer';

export function parsePrUrl(value) {
  if (typeof value !== 'string' || value.length > 300) throw new Error('Use a canonical public GitHub PR URL.');
  const match = /^https:\/\/github\.com\/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))\/([A-Za-z0-9_.-]{1,100})\/pull\/([1-9][0-9]{0,9})\/?$/i.exec(value.trim());
  if (!match || ['.', '..'].includes(match[2])) throw new Error('Use https://github.com/owner/repo/pull/123, without query parameters or a fragment.');
  const [, owner, repo, number] = match;
  return {owner, repo, number, repository: `${owner}/${repo}`, url: `https://github.com/${owner}/${repo}/pull/${number}`, api: `https://api.github.com/repos/${owner}/${repo}/pulls/${number}`};
}

export const SCENARIOS = {
  preview: {
    description:'Mira sends Leo a ticket. A chat app previews the link.',
    ruleLabel:'After a preview, the ticket must belong to',
    expectations:[['mira','Mira (sender)'],['leo','Leo (recipient)']],
    actionLabel:'When the chat app opens the link',
    actions:[['read','Show the invitation'],['accept','Accept the transfer, then show it']],
    defaultExpectation:'mira', defaultAction:'read'
  },
  recipient: {
    description:'The ticket is for Leo. Alex tries to accept it.',
    ruleLabel:'An account other than Leo must be',
    expectations:[['blocked','Blocked'],['allowed','Allowed to accept']],
    actionLabel:'When someone accepts the ticket',
    actions:[['recipient','Check they are Leo'],['anyone','Allow any signed-in account']],
    defaultExpectation:'blocked', defaultAction:'recipient'
  }
};

export function runDraft({scenario, expectation, action}) {
  const spec = Object.hasOwn(SCENARIOS,scenario) && SCENARIOS[scenario];
  if (!spec || !spec.expectations.some(([value]) => value === expectation) || !spec.actions.some(([value]) => value === action)) throw new Error('Unsupported draft rule or action.');
  const state = simulateFlow(scenario === 'preview'
    ? {preview:action, acceptance:'recipient', visitor:'preview'}
    : {preview:'read', acceptance:action, visitor:'alex'});
  const actual = scenario === 'preview' ? state.owner : state.denied ? 'blocked' : 'allowed';
  const label = value => scenario === 'preview' ? `Owner: ${value === 'mira' ? 'Mira' : 'Leo'}` : value === 'blocked' ? 'Alex is blocked' : 'Alex can accept';
  return {
    passed:actual === expectation,
    expected:label(expectation), observed:label(actual),
    before:'Mira', after:state.owner[0].toUpperCase()+state.owner.slice(1),
    message:scenario === 'preview'
      ? state.accepted ? 'The preview transferred the ticket.' : 'The preview left ownership unchanged.'
      : state.denied ? 'Alex was denied. Mira keeps the ticket.' : 'Alex accepted a ticket intended for Leo.'
  };
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
  return {owner, accepted, denied};
}
