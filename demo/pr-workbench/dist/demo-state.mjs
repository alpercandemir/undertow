import {parseCases, replay} from './refund.mjs';

export const PRESETS = {
  overflow:{id:'cumulative-cap',captured_minor:10000,operations:[{key:'refund-1',amount_minor:8000},{key:'refund-2',amount_minor:3000}]},
  retry:{id:'idempotent-retry',captured_minor:10000,operations:[{key:'same-operation',amount_minor:2500},{key:'same-operation',amount_minor:2500}]},
  boundary:{id:'exact-remaining',captured_minor:10000,operations:[{key:'refund-1',amount_minor:8000},{key:'refund-2',amount_minor:2000}]}
};

export function parseTrace(text) {
  if (typeof text !== 'string' || text.length > 50000) throw new Error('Trace must be JSON, at most 50 KB.');
  return parseCases(JSON.stringify([JSON.parse(text)]))[0];
}

export function evaluateTrace(text, subject) {
  const trace = parseTrace(text);
  const result = replay(trace,subject);
  const partial = result.operations.filter(op => op.amount_minor > 0 && op.amount_minor < trace.captured_minor);
  return {...result,partial:partial.length ? partial.some(op => ['settled','duplicate'].includes(op.status)) ? 'accepted' : 'not accepted' : 'not exercised'};
}

function validateState(value) {
  if (!value || typeof value !== 'object' || Object.keys(value).length !== 3 || value.v !== 1 || !['legacy','broken','fixed'].includes(value.subject) || !Object.hasOwn(value,'trace')) throw new Error('Unsupported shared scenario.');
  return {v:1,subject:value.subject,trace:parseTrace(JSON.stringify(value.trace))};
}

export function encodeScenario(subject, text) {
  const value = validateState({v:1,subject,trace:parseTrace(text)});
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  if (bytes.length > 6000) throw new Error('This trace is too large for a share link. Export it from the workspace instead.');
  return btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'');
}

export function decodeScenario(encoded) {
  if (typeof encoded !== 'string' || encoded.length > 8000 || !/^[A-Za-z0-9_-]+$/.test(encoded)) throw new Error('Invalid scenario link.');
  const base64 = encoded.replaceAll('-','+').replaceAll('_','/');
  const bytes = Uint8Array.from(atob(base64),char => char.charCodeAt(0));
  return validateState(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)));
}
