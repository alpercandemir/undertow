# Tool contracts

The authoritative argument schemas are bundled in `src/main/resources/schemas/tools.json`; the final finding contract is `review.json`. Unknown tools and additional fields fail closed.

| Tool | Required inputs | Result / boundary |
|---|---|---|
| get_diff | none | Up to 200 changed files, 80k characters across supported hunks; skipped/truncated paths stay visible |
| read_source | snapshot, path, start_line, end_line | 1–200 lines, 24k characters, regular pinned Git blob; source never executes |
| find_references | snapshot, symbol | JavaParser declarations/calls/names/annotations; at most 100 files/references; syntactic resolution only |
| get_rule | id | Trusted catalog entry and source policy sections at base |
| get_business_context | none | Owner-provided invariants from base |
| get_ci_evidence | none | Optional caller-supplied commit/hash-checked evidence; authenticity explicitly qualified |
| compare_dependencies | none | Direct literal POM comparison or resolved trees supplied for both snapshots; paths and scopes preserved |
| fetch_migration_evidence | coordinate, old_version, new_version | Only pairs in the pinned change inventory and official coordinate registry; failed/missing sources become coverage gaps |
| query_vulnerabilities | coordinate, version | Exact version present in inventory; OSV match distinct from reachable exploitation |

Each successful tool returns `{evidence_id, data}`. Evidence stores kind, origin, pinned commit, content hash, retrieval time and content. Network retrieval disallows redirects, non-HTTPS URLs, internal addresses and unregistered hosts. Model-supplied arbitrary URLs have no interface.

Every finding cites diff evidence and a read_source range covering its pinned location in a changed file. Deleted files use base locations. Migration findings additionally cite retrieved documents. Source evidence plus an edit does not by itself prove behavior: findings must explain causation, assumptions and confidence.

CI bundles retain `schema_version: 1` compatibility and support trusted-import v2 with repository/run/artifact identity, pinned base/head, and compact-JSON content hashes. Accepted kinds are dependency_tree/compiler/tests/spotbugs/build/oracle. `scripts/capture-ci.py` retains unverified local dependency-tree compatibility; the separate trusted importer checks workflow/artifact origin. Build/oracle records bind exact subjects and approved digests. The dispatcher never executes a bundle or extracts an archive. See [contracts](contracts.md) and [approved regression execution](assurance.md#approved-regression-artifacts) for the separate CI-only boundary.

`compare_library_api` is deferred. Generated regression tests are specifications only. Deterministic project tests run outside the reviewer tool loop.
