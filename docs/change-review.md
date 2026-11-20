# Review a Java/Maven change

Open a PR to see which Java operations the change reaches, what the selected inputs return on each commit, and where execution leaves questions open. Undertow selects its scope from the two revisions and their tests. The comparison requires no profiles, rules or package paths.

## Install in GitHub Actions

Copy [the workflow](examples/change-review.yml) to `.github/workflows/change-review.yml` and set `java-version` to the version your project already uses. The action accepts Java 8, 11, 17, 21 and 25. It installs a separate JDK for source discovery. The project keeps its own Java version, Maven configuration and tests. If the checked revision provides an executable Maven Wrapper, Undertow uses it.

The workflow runs when a PR opens or receives a new commit. Undertow checks out the exact head and compares it with the merge base of that head and the event's target commit. Open the check's **Details** for the summary or download the **undertow-change-review** artifact for the raw results. The sample uses a version tag. For an immutable dependency pin, use the complete commit SHA listed on the [release](https://github.com/alpercandemir/undertow/releases/tag/v0.1.1-alpha.1).

| Input | Default | Purpose |
|---|---|---|
| `java-version` | `21` | Project JDK used to build and run tests |
| `java-distribution` | `temurin` | Project JDK distribution understood by setup-java |
| `base`, `head` | PR event | Supply both full commit SHAs for a manual run, such as on `workflow_dispatch` |
| `fail-on-breakage` | `true` | Mark a reproduced breakage as a failed check. Set to `false` for an advisory rollout. |
| `artifact-name` | `undertow-change-review` | Choose distinct names if multiple jobs call the action |

Run on an ephemeral GitHub-hosted Linux runner with `contents: read` and credential persistence disabled. Use the `pull_request` event and keep publishing credentials and other secrets out of the job. Java and Maven execute each revision's build and test code under the runner user's privileges. See [execution boundaries](../SECURITY.md).

## Run locally or from another CI

Download the portable ZIP and SHA-256 file from the [release](https://github.com/alpercandemir/undertow/releases/tag/v0.1.1-alpha.1). Verify the checksum before extracting.

```sh
shasum -a 256 -c undertow-change-review-0.1.1-alpha.1.zip.sha256
unzip undertow-change-review-0.1.1-alpha.1.zip
cd undertow-change-review-0.1.1-alpha.1
scripts/undertow compare --repo /path/to/project --base BASE_SHA --head HEAD_SHA \
  --analysis-java-home /path/to/jdk-25 --build-java-home /path/to/project-jdk \
  --output /path/to/new-evidence-directory
```

The local package needs Python 3.10+, Git, Maven 3.9+ or the project's compatible wrapper, and a full JDK 17+ for parsing. Use a parser JDK recent enough to understand your source syntax. When one installed JDK supports both tasks, omit the two JDK options and use `JAVA_HOME`. The runner records the JDKs and resolves both Git references before reading their tracked blobs. Each Maven invocation uses a fresh copy of one revision.

## Read the evidence

| Result | What was established |
|---|---|
| Reproduced breakage | A repository assertion held on the base and failed on the candidate in three fresh paired runs. Undertow uses the existing test when it already reproduces the failure. When a portable scenario supplies the evidence, Undertow validates and exports its JUnit test. |
| Observed behavioral difference | Both revisions ran the same generated input and produced different scalar results or exception classes. A candidate-only assertion or an input variation can establish a difference. The previous outcome is available as a characterization to review. |
| Potential impact | Java syntax links the change to one or more operations. Paired execution covered only part of the impact or could not produce a comparable observation. |
| Incomplete comparison | A required checkout, discovery, build or generated run could not supply comparable execution evidence. See the investigation gaps and artifacts. |
| No difference observed | The selected scenarios agreed within the reported scope. |
| No executable change found | The inspected paths and Java scope yielded no changed operation to execute. |

The CLI exits 1 for reproduced breakage, 2 for incomplete comparison and 0 for other review outcomes. The action shows an incomplete comparison as a warning and retains its evidence. The summary identifies gaps even when another scenario produced evidence. Review the result before using this experimental job as a required PR check.

Open `summary.md` first. `report.json` stores the resolved hashes, tool fingerprint, selected calls and tests, individual outcomes, limits and replay attempts. Logs, the exact diff and Java discovery records are retained alongside it. The `regressions/` directory mirrors the destination path for each validated standalone JUnit test. Keep a proposed test through the consumer's normal review if its expectation is worth preserving.

## Supported scope

- Single-module projects with a root `pom.xml`, standard Maven Java source roots and standard Surefire reports. Both revisions need compatible builds and dependencies. Symlinks and submodules require an environment outside this increment.
- Java syntax comes from the JDK parser with annotation processing disabled. The scanner follows syntactically identifiable named calls for up to three hops. Imports, field and parameter types help resolve owners. Method overloads, callback dispatch and data or configuration relationships can require follow-up.
- Compatible public calls in direct JUnit 4 or 5 assertions seed generated probes. Probes support literal inputs and scalar values or exception classes. Small string variations add repeat and rejection cases. Existing repository tests can still run when their fixtures are too involved to turn into a generated scenario.
- For production Java source changes, an unchanged, selected JUnit method can establish a breakage. Undertow replays the same method and matches an assertion failure to the same source line across all fresh candidate runs.
- The report records its budgets. Up to 1,500 Java files per revision, eight existing test methods, 32 generated inputs and three exported findings are considered. Each Maven invocation has a two-minute limit. This deterministic strategy uses the JDK parser and makes no model calls.

See [evaluation](change-review-evaluation.md) for immutable comparisons and their observed limits.
