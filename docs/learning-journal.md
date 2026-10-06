# Learning journal — September 30, 2026

The first implementation keeps one reviewer and an explicit application-owned loop. The SDK generates native function calls but never dispatches them. This makes permissions and budgets testable at one narrow boundary.

Pinning Git objects avoids running a checkout and avoids following working-tree symlinks. The base policy is loaded before any model call, and head edits cannot change it. Model instructions and permissions remain independent of comments, release notes and CI artifacts.

Evidence IDs are useful only with validation. Findings require source ranges and diff evidence, plus retrieved documents for dependency claims. High Critical is constrained by declared catastrophic scope. Regression specifications remain proposed: the tool interface cannot execute them.

Maven literals are not the effective dependency graph. The implementation resolves local property strings only as literal evidence, and preserves unresolved parent/BOM/profile/transitive gaps. Resolved Maven trees enter through an optional hash-checked bundle produced outside the secret-bearing reviewer. Local hashes do not establish runner authenticity.

The first Docker integration run revealed a real compatibility boundary: Testcontainers 1.20.6 failed against Docker 29's API. Updating to 1.21.4 allowed the existing rollback/concurrency/rejection behavior tests to pass. This is a useful example of version-specific evidence and execution, rather than treating a major version number as impact. See the [official Testcontainers release](https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4).

Authored replay responses reproduce dispatch and validation deterministically. Forty labeled fixtures and three runner modes do not by themselves establish model accuracy. The live Gemini smoke request returned HTTP 401; until credentials are usable, held-out precision, recall, repeatability, cost and regression usefulness remain unmeasured.

GitHub CLI authentication was available outside the sandbox. The initial sandbox result was therefore not reliable evidence of invalid GitHub credentials. Ordinary hosted CI passed on the public repository, including both builds and 120 replay runs. The first hosted attempt exposed an invalid setup-java commit pin; checking the official release tag and using its verified commit fixed the setup failure. Hosted agent-review/comment checks remain open; mocked tests cover stale heads, repeated summaries and attacker-owned comment markers locally.

Dependency smoke validation retrieved immutable Jackson 2.17.2/2.18.3 release-note sources and queried OSV for the exact coordinate/version. Full bounded payload hashes were recorded, while 24k-character excerpts were marked truncated. The module continues to report incomplete interval/application/API coverage instead of inferring universal compatibility.
