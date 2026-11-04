export function parsePrUrl(value) {
  if (typeof value !== 'string' || value.length > 300) throw new Error('Use a canonical public GitHub PR URL.');
  const match = /^https:\/\/github\.com\/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))\/([A-Za-z0-9_.-]{1,100})\/pull\/([1-9][0-9]{0,9})\/?$/i.exec(value.trim());
  if (!match || ['.', '..'].includes(match[2])) throw new Error('Use https://github.com/owner/repo/pull/123, without query parameters or a fragment.');
  const [, owner, repo, number] = match;
  return {owner, repo, number, repository: `${owner}/${repo}`, url: `https://github.com/${owner}/${repo}/pull/${number}`, api: `https://api.github.com/repos/${owner}/${repo}/pulls/${number}`};
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
