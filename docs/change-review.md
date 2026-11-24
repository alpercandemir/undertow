# Business change review

Undertow compares the committed source for two Git revisions. Each finding shows an input or precondition, the inferred result on each revision, and a question to resolve before merging. Source links identify the commit, path, and line range behind the finding.

## GitHub Actions

Copy the [workflow](examples/change-review.yml) into `.github/workflows/undertow.yml` on the base branch. It uses Undertow `v0.4.0`. You can pin the corresponding full commit SHA for immutable action selection. The action fetches the event commits into a temporary bare Git repository. It compares the PR's merge base to its exact head and writes the result to the job summary and to a Markdown and JSON artifact.

The example updates one GitHub Actions bot comment per PR. It uses `pull_request_target` with `contents: read` and `pull-requests: write` so the trusted workflow can also report on fork PRs. The action never checks out or executes the candidate. Keep this job separate from jobs that build or run PR code. If your repository's Actions policy restricts `pull_request_target`, allow this trusted reporting workflow there or use `pull_request` with `contents: read` and remove the `comment` input for a summary-only setup. [GitHub's event security guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)

The action checks that the PR is still open and that its base and head match the event before publishing. A write failure appears in the summary. Findings, missing model access, and unavailable analyses are advisory. On a push event, the action uses the event's before and after commits if both exist. For workflows dispatched manually, pass both `base` and `head` as full commit SHAs.

For observed evidence from a focused pytest case, run the [separate test replay Action](test-replay.md) on a read-only build job. An optional publisher adds the measured values and test verdicts to a separate comment in the PR conversation.

## Review priority

The summary displays up to twelve findings. Undertow reserves space for local findings and fills any remaining slots with Gemini findings in priority order. It orders the selected findings by priority, shows the first in full, and makes the others expandable. Each shows the same scenario on both revisions, the potential impact, the review question, and the evidence and its limits.

| Priority | Local evidence | Review question |
| --- | --- | --- |
| P1 | A removed route declaration, or a recognized eligibility function whose example changes from true to false | Which existing client or request may lose access? |
| P2 | A changed outcome in existing logic | What does the same input receive now? |
| P3 | An added route declaration | Which new entry point is available? |

Gemini uses the same review priorities and supplies a source-based rationale. Priorities express review order. A difference may reflect an intentional change, and defect status remains undetermined. An empty report means Undertow found no differences within its reviewed scope and analysis capabilities. Behavior changes may still exist.

## Evidence levels

| Level | Meaning | Emitted today |
| --- | --- | --- |
| `source_inferred` | The example and its outcomes were inferred from committed source. | Yes, for both local rules and Gemini. |
| `test_verified` | Executed tests verified the claimed outcomes. | Reserved. |
| `runtime_verified` | The outcomes were observed by running the same scenario on both revisions. | Reserved. |

The Action reads Git objects and runs no code or tests from either revision. A static call binding describes a source relationship. Downstream outcomes still require review. A same-name match is an investigation lead.

## Report JSON and compatibility

The first explicit report schema is `schema_version: 1`. Unversioned v0.3.0 artifacts remain readable through the legacy fields. The change is additive. `version` still identifies the tool version. Action inputs, outputs (`status`, `base`, `head`, `comment`), artifact filenames, and comment publication stay compatible. Consumers using strict JSON validators should allow the new fields. Use `status` to distinguish `findings`, `no_findings`, and `unavailable`, including when the findings list is empty.

| Finding field | Meaning |
| --- | --- |
| `id` | Identifier within the report, such as `F1`. Identifiers follow review order and may change across runs. |
| `analysis_mode` | `local` or `gemini`, identifying the analysis that produced the finding. The top-level `mode` records whether Gemini completed the comparison. |
| `classification` | `behavioral_difference`, `route_declaration_added`, or `route_declaration_removed`. Gemini findings use `behavioral_difference`. |
| `compatibility_risk` | `potentially_breaking` for P1, `changed_outcome` for P2, or `additive` for P3. These are source-based review categories. |
| `example` | `input` is a concrete hypothetical input, sequence, or value at the edited logic. `precondition` is used when the analysis can only establish a condition such as reaching a statement or matching a parameterized route. The unused field is `null`. `base_outcome` and `head_outcome` describe comparable results for the same scenario. |
| `evidence_level` and `evidence` | Evidence strength and source-reference IDs. Resolve IDs in the top-level `evidence` map for `base` and/or `head` paths and inclusive line ranges. A route declaration can have evidence on only the revision that contains it. |
| `potential_impact` and `review_question` | A possible consequence for consumers or policy and a decision to verify before merging. |
| `analysis_limitations` | Finding-specific caveats about what the example supports. The report-level array describes limits common to the comparison, and `coverage` and `notes` record scope and omissions. |

Existing fields such as `priority`, `priority_reason`, `flow`, `before`, `after`, `scenario`, `example_before`, and `example_after` remain available. `related_references` retains the existing relationship values and per-revision bindings. The evaluation runner fingerprints the whole finding, so new fingerprints reflect the added fields. Use the pinned worker for historical adjudications.

See the [Markdown example](examples/summary.md) and [JSON example](examples/report.json) from a synthetic comparison. Its links illustrate how source references resolve for a repository hosted on GitHub.

## How it reads changes

The engine identifies changed text on both revisions and ignores conventional test and fixture paths, documentation, common generated paths, and sensitive file paths. It takes bounded regions from the remaining differences, including surrounding lines. Source file names and content come from Git objects. Neither the model nor the local engine runs project code.

The local rules share a bounded, dependency-free source engine. It reads Git blobs and uses small lexical rules for syntax such as comments, declarations, and imports. The Action needs Python and Git and analyzes source without executing it. The report records the reviewed scope and any omissions caused by its limits.

| Local pattern | Coverage |
| --- | --- |
| Numeric comparisons | Standalone `if`, `elif`, `else if`, and `return` comparisons with a simple left operand and one decimal integer threshold. Parenthesized expressions can span up to 12 lines and 1,400 characters. The engine compares integer inputs and links the statement on both revisions. |
| Named thresholds | A changed top-level uppercase integer assignment in Python or a top-level uppercase `const` in JavaScript or TypeScript can resolve to a comparison in a callable in the same file. The binding must be unambiguous and the predicate present on both revisions. The report links the declaration and predicate and shows the resolved values. For `RETURN_WINDOW_DAYS = 30` changing to `14`, `age_days = 30` distinguishes the old and new result of `age_days <= RETURN_WINDOW_DAYS`. |
| Literal returns and routes | Simple replaced literal return values and added or removed HTTP route declarations. |

Route comparison uses the source file and the receiver on the declaration, when present, alongside the HTTP method and path. If two receivers declare the same method and path, the report identifies the affected source declarations. Deployment and router mount prefixes still require review.

Numeric examples report return values when the local rules can infer them on both revisions. Those citations include the statements that support the return values. Otherwise the example compares the predicate results and explains that return values are unverified. Compound expressions, computed thresholds, imported constants, configuration tables, helper-local assignment tracking, and arbitrary data flow remain outside the local rules.

### Caller tracing

Undertow searches tracked source with `git grep` on both revisions. It inspects target modules first, followed by files with matching import lines and files in nearby directories. Within each hop, verified calls rank above candidate calls. Each reference includes the target path and the relationship found on each revision.

| Relationship | Source evidence |
| --- | --- |
| **Static binding** (`verified`) | A direct call to an unambiguous top-level Python or JavaScript-family function in the same file, or a supported import of that function. Imports include simple Python absolute and relative imports and JavaScript or TypeScript named or namespace imports with explicit relative file paths. |
| **Name match** (`candidate`) | A matching call with an unconfirmed binding. Known competing declarations or imports suppress unrelated bare matches. Common names such as `get`, `run`, and `apply` require a supported binding. |

For absolute Python imports, Undertow checks the repository root and the conventional `src/` source root. It requires one tracked module or package across those locations. Relative imports resolve within their package.

Only calls verified on both revisions expand the trace. Undertow follows up to three hops, inspects up to eight matched files per hop alongside the target modules, and keeps up to 16 references within a 24 KiB context budget. Dynamic dispatch, package re-exports, custom module resolution, and more complex import or shadowing patterns can leave gaps. A call relationship describes source structure. Effects on an enclosing caller still depend on its own logic.

### Optional model interpretation

For broader business-flow analysis, add a repository Actions secret named `GEMINI_API_KEY` and pass it through the Undertow step's environment. This explicitly opts into sending changed source, filenames, and selected supporting excerpts to the Gemini Developer API. Obvious credential patterns are filtered. Check Google's data terms for your API tier before sharing private source. The default model is `gemini-3.8-flash`. Set `UNDERTOW_GEMINI_MODEL` on the step to use a different Gemini model. [Gemini model documentation](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash) · [Gemini API terms](https://ai.google.dev/gemini-api/terms)

```yaml
- uses: alpercandemir/undertow/actions/change-review@v0.4.0
  env:
    GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}
```

The model groups related changes across files. It can request nearby source and a lookup of up to four identifiers already present in the review. Undertow supplies excerpts from the automatic reference trace and searches for requested symbols within the same bounded process. Excerpts can include unchanged callers or definitions. The model receives only selected excerpts. Every model finding cites a changed region and can also cite supporting source. In `v0.4.0`, accepted model findings supplement the local findings. If the model is unavailable or returns unusable output, the report contains the local findings and the fallback reason. The lookup can miss dynamic calls, indirect dependencies and matches beyond its budget.
