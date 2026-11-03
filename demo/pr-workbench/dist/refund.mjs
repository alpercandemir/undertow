export function parseCases(text) {
  if (text.length > 50000) throw new Error('Replay input exceeds 50 KB.');
  const cases = JSON.parse(text);
  if (!Array.isArray(cases) || !cases.length || cases.length > 20) throw new Error('Provide 1–20 replay cases.');
  const ids = new Set();
  const keys = (object, required) => object && typeof object === 'object' && !Array.isArray(object)
    && Object.keys(object).length === required.length && required.every(key => Object.hasOwn(object,key));
  for (const item of cases) {
    if (!keys(item,['id','captured_minor','operations']) || typeof item.id !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(item.id) || ids.has(item.id)) throw new Error('Each case needs a unique id, captured_minor, and operations.');
    ids.add(item.id);
    if (!Number.isSafeInteger(item.captured_minor) || item.captured_minor <= 0 || item.captured_minor > 1e12 || !Array.isArray(item.operations) || !item.operations.length || item.operations.length > 100) throw new Error('Use positive integer captures ≤ 10^12 and 1–100 operations per case.');
    for (const op of item.operations) {
      if (!keys(op,['key','amount_minor']) || typeof op.key !== 'string' || !op.key.trim() || op.key.length > 100 || !Number.isSafeInteger(op.amount_minor) || Math.abs(op.amount_minor) > 1e12) throw new Error('Each operation needs a key and an integer amount_minor (absolute value ≤ 10^12).');
    }
  }
  return cases;
}

// Browser model of the three reviewed fixtures; never executes edited Java.
export function replay(item, variant) {
  if (!['legacy','broken','fixed'].includes(variant)) throw new Error('Unknown fixture implementation.');
  parseCases(JSON.stringify([item]));
  let refunded = 0;
  const settled = new Map();
  const operations = item.operations.map(op => {
    let status;
    if (settled.has(op.key)) status = settled.get(op.key) === op.amount_minor ? 'duplicate' : 'rejected';
    else if (op.amount_minor <= 0 || variant === 'legacy' && op.amount_minor !== item.captured_minor
      || op.amount_minor > (variant === 'broken' ? item.captured_minor : item.captured_minor - refunded)) status = 'rejected';
    else {status = 'settled'; settled.set(op.key,op.amount_minor); refunded += op.amount_minor;}
    return {...op,status,total_minor:refunded,cap_holds:refunded <= item.captured_minor};
  });
  return {id:item.id,captured_minor:item.captured_minor,refunded_minor:refunded,effects:settled.size,cap_holds:operations.every(op => op.cap_holds),operations};
}

export function parseManifest(text) {
  if (text.length > 50000) throw new Error('Contract draft exceeds 50 KB.');
  const value = JSON.parse(text);
  if (!value || value.schema_version !== 1 || !Array.isArray(value.contracts) || !value.contracts.length || !Array.isArray(value.oracle_paths) || typeof value.repository !== 'string' || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(value.repository)) throw new Error('Expected a version 1 contract manifest.');
  const allowedRoot = ['schema_version','repository','oracle_paths','oracle_roots','regression_artifacts','contracts'];
  if (Object.keys(value).some(key => !allowedRoot.includes(key))) throw new Error('Unknown manifest field. Use the CLI for full schema validation.');
  const ids = new Set();
  for (const contract of value.contracts) {
    if (!contract || typeof contract.id !== 'string' || !/^[A-Z][A-Z0-9-]{1,79}$/.test(contract.id) || ids.has(contract.id) || contract.verifier !== 'junit' || typeof contract.expectation !== 'string' || !contract.expectation.trim() || typeof contract.owner !== 'string' || !contract.owner.trim() || !Array.isArray(contract.tests) || !contract.tests.length) throw new Error('Each contract needs a unique id, owner, expectation, and JUnit bindings.');
    ids.add(contract.id);
    const allowed = ['id','version','owner','expectation','assumptions','scope','verifier','tests'];
    if (Object.keys(contract).some(key => !allowed.includes(key))) throw new Error('Unknown contract field.');
    for (const test of contract.tests) if (typeof test.id !== 'string' || !Array.isArray(test.sources) || !test.sources.length) throw new Error('Each binding needs an exact test id and sources.');
  }
  return value;
}

export function testStatus(fixture, variant, id) {
  const matches = fixture.variants[variant].tests.filter(test => test.name === id);
  return matches.length === 1 && ['passed','failed'].includes(matches[0].status) ? matches[0].status : 'incomplete';
}

export function discoveryDraft(candidate) {
  return {
    schema_version:1, repository:'example/refund-extraction', oracle_paths:['pom.xml'],
    contracts:[{id:'DISCOVERED-REFUND',version:'1',owner:'UNASSIGNED — review required',expectation:candidate.expectation,
      assumptions:['Candidate from test metadata, not an approved business invariant.'],
      scope:['src/main/java/example/refunds/RefundService.java'],verifier:'junit',
      tests:[{id:candidate.test_id,sources:[candidate.source]}]}]
  };
}
