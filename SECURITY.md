# Security and data handling

## Credentials and public source

Provider keys, GitHub tokens, App private keys, OAuth/webhook secrets, database encryption keys, and operator credentials must be supplied outside source control. Example configurations contain environment-variable names and nonfunctional placeholders. Test credentials are synthetic. The interactive order/payment demo requires an explicit `DATABASE_PASSWORD`; it supplies no fallback password.

`.gitignore` excludes local environment files, common private-key formats, local credentials, service databases, build output, and generated review directories. The customer application's `.undertow/` directory is intentionally tracked because it contains inert company-policy examples, not runtime artifacts. Ignore rules do not erase previously committed content.

Before publishing changes, run:

```sh
python3 scripts/public-check.py
gitleaks git . --log-opts=--all --redact --no-banner
python3 scripts/public-check.py --export /path/to/new-audit-directory
gitleaks dir /path/to/new-audit-directory --redact --no-banner
```

The content check examines tracked files and nonignored untracked files, flags Turkish characters, personal workstation paths, local credential/runtime filenames, and exact credential values inherited through the process environment. Gitleaks scans common secret formats and entropy patterns in candidate files and available Git history. CI pins Gitleaks 8.30.1 by archive SHA-256 and runs both scans without provider credentials. Scanner output must remain redacted. A clean scan is supporting evidence, not a guarantee that arbitrary confidential information is absent.

Review public samples manually for real customer data, internal infrastructure, private repository links, screenshots, and recordings. Historical blobs can retain content removed from current files; remote tags, releases, workflow artifacts, and external copies require separate review. Do not change repository visibility until that review is complete. See the [public-source audit](docs/public-source-audit.md) for this checkout's scope and remaining history considerations.

If a real credential was committed, revoke or rotate it before removing the value and cleaning history. Do not assume deletion or a history rewrite makes an exposed credential safe. Rewriting existing history changes commit IDs and requires coordinated handling of clones and remote refs.

## Review and publication boundaries

Undertow sends reviewed source excerpts to the configured Gemini API during live reviews. Local replay and collector modes require no provider credentials. Provider retention and billing depend on your account; review those settings before submitting private code. Undertow has no paid-model fallback and its token estimates are application limits, not guaranteed billing caps.

Reviewed code, comments, release notes and build artifacts are untrusted data. The tool registry cannot execute shell commands, write source or publish comments. Reads use immutable Git objects, validated repository-relative paths, regular blobs, bounded output and fixed argument arrays. Trusted policy comes from the reviewed base commit; protect that branch.

Migration retrieval uses fixed official host and coordinate mappings, HTTPS, disabled redirects, time/size limits and public-address checks. Source references and hashes record observations, not proof that an advisory is exploitable. Local CI bundles verify declared commits and content hashes but cannot establish workflow authenticity; only use evidence whose runner origin you independently trust.

Trace JSONL contains stage/tool names, durations, outcomes and evidence IDs, without source or credentials. `evidence.json` contains excerpts and may contain sensitive information from the repository. Store artifacts with access/retention appropriate for the reviewed code. Reports are advisory, and partial/failed reviews remain visibly incomplete.

The maintainer workflow builds only its trusted dispatch commit, reads PR Git objects without checkout, and scopes the model secret to the review step. A separate publisher job consumes an allowlisted, hashed same-run artifact, checks repository/PR/commit provenance, validates findings and escapes Markdown. It uses PR write access only for its marked summary. A head change before posting prevents publication; a change during posting marks the result stale. Concurrent publishing is serialized by PR.

For a discovered security issue, contact the repository maintainer privately using their published contact channel; no reporting address is embedded in this unreleased project.
