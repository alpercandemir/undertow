# Public-source audit

Date: October 6, 2026. Scope: this checkout's publishable source, original main/tag history, and prepared public release.

## Findings and changes

- Gitleaks 8.30.1 found no secrets in the original eight publication commits. A separate byte comparison found no inherited credential values in candidate source files or 334 historical blobs.
- The initial current-file scan flagged PEM delimiters in the App key loader. The matched text was parsing code, not key material. The loader now constructs its delimiters explicitly; candidate-file scans pass without a suppression or baseline.
- A terminal recording contained a personal workstation path. It now uses a portable relative path, and future recordings normalize that output.
- Private pilot repository links and bot comment identifiers were removed from public status documentation. The synthetic scenarios and implementation limits remain documented.
- The order/payment application no longer supplies a fallback database password. Interactive operation requires `DATABASE_PASSWORD`.
- No Turkish text was found in publishable source or original commit subjects. Documentation and contribution guidance require English. Generated local demonstrations remain ignored.
- The owner-supplied logo has no PNG text chunks. Its EXIF fields contain only color-space and image-dimension information; its bytes contain no matching credential or personal path.

The README now explains supported scope, the offline quick start, real Gemini requests, failure reporting, GitHub integration, the internal hosted preview, and current limitations. It does not claim live-model accuracy or completed external pilot acceptance.

Release archives were inspected recursively for exact inherited credentials and private-key material. Google HTTP Client bundles publicly distributed `TestCertificates` signing fixtures, and a dependency includes its upstream author's build path. These are third-party test/provenance data, not Undertow credentials or customer information. They must not be described as leaked production secrets or used for real authentication. Release assets are regenerated from the reviewed source and current executable JAR.

## Preventing accidental publication

Ignore rules cover environment variants, private-key formats, local credentials, embedded service databases, build output, and generated reviews. The tracked customer `.undertow/` files are inert example policy.

`scripts/public-check.py` checks tracked and nonignored candidate files for prohibited local data, Turkish characters, personal paths, private pilot links, and exact inherited credential values. CI additionally scans candidate files and available Git history with checksum-pinned Gitleaks. No broad secret-scanning suppression was added.

Scan output is redacted. Synthetic test credentials, variable names, public API URLs, placeholder domains, public maintainer attribution, and fixture hashes are retained intentionally.

## History and release handling

The owner authorized consolidating all work into one public root commit. The original main/tag history and previous release assets are backed up outside the repository for recovery. The public branch and release tag must point only to the sanitized root; application checkpoint refs and recovery backups must never be pushed.

Removing content from a current file does not remove historical blobs. Consolidation removes the old workstation path and private pilot links from the history being published. Existing remote caches, old workflow logs/artifacts, and external copies are separate surfaces; rewriting a branch does not guarantee their deletion. No real leaked credential was identified by this audit.

The private pilot is disposable synthetic test infrastructure. Its deletion was attempted under owner authorization but GitHub rejected the CLI credential because it lacks `delete_repo`; the repository remains private unless deleted separately.

## Evidence and limits

Final publication verification passed all 94 harness tests, formatting, SpotBugs with zero findings, 21 Python helper tests, and four Java 21 payment behavior tests. The offline README example produced one advisory finding; recording output uses a portable path. Docker-dependent database integration tests were not rerun locally. Authored replay remains a harness check, not a model-quality measurement.

The audit checks for known secret patterns and reviewed metadata. It cannot certify the absence of every possible confidential fact or data hidden by arbitrary encoding. Do not upload local reports, source archives from other projects, credentials, or recovery bundles with the public release.
