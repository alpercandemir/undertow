# Undertow evaluation

The [benchmark results](benchmark.md) compare local analysis and Copilot CLI on the same revision pairs, with per-finding judgments and saved run records. The [competitive pilot instructions](competitive-pilot.md) cover reproduction and independent assessment.

## v0.3.0 historical baseline

On November 20, 2026, the released worker at `cbf43c4b7174c306c9f24bf4f3ebcb5d400a48a6` ran in credential-free local mode on 20 merged public PRs. The [case manifest](historical.json) pins the actual merge commit and its first parent for each PR, including squash merges. It contains 10 Click PRs and 10 HTTPX PRs, with nine bug fixes, seven intentional changes and four runtime behavior controls. The sample was selected for clear upstream evidence and includes a private-helper refactor, an annotation-only change and documentation/test controls. [Click changes](https://click.palletsprojects.com/en/stable/changes/) · [HTTPX changelog](https://github.com/encode/httpx/blob/master/CHANGELOG.md)

The [run record](baselines/v0.3.0-local.json) shows 20 completed comparisons, 18 with reviewable source regions, and **zero findings**. The 16 behavior-changing PRs contain 18 labeled outcomes, none of which the worker reported. The labels use upstream diffs and tests as evidence. They have not had a second review or been independently replayed against both revisions.

| Metric | v0.3.0 local result |
| --- | --- |
| Precision | Undefined. No findings to adjudicate. |
| Recall on supported patterns | Undefined. All 18 labeled outcomes fall outside the v0.3.0 local patterns. |
| Incorrect findings per PR | 0 across 20 comparisons |
| Incorrect findings per runtime control | 0 across 4 controls |
| Signal quality | Undefined. No findings with examples. |
| Actionable yield | Unmeasured. No reviewer follow-through was collected. |
| Median worker time | 0.69 s per PR on the recorded local host, including Git reads and report writes |
| Model cost | No model calls in the local run |

The Gemini baseline is pending. No `GEMINI_API_KEY` was available for a live run. Mocked requests in CI exercise fallback and request validation. The historical sample is small, selected for clear upstream evidence, and Python-only. Its results describe these comparisons.

## Local rules on the same corpus

The [local run with threshold and caller tracing](baselines/thresholds-and-callers-local.json) reused the frozen manifest, labels, mode, and exact Git revisions from v0.3.0. The worker was snapshotted from the checkout and identified by `worker_sha256` in the record.

| Historical metric | v0.3.0 | Local rules with threshold and caller tracing |
| --- | ---: | ---: |
| Completed compared PRs | 20 | 20 |
| PRs with reviewable source | 18 | 18 |
| Findings | 0 | 0 |
| Labeled outcomes detected | 0 of 18 | 0 of 18 |
| New false positives | — | 0, including all 4 runtime controls |
| Median worker time on the recorded host | 0.69 s | 0.18 s |

The historical sample yielded zero additional true positives. Precision and recall against the frozen supported-pattern labels remain undefined. The run used local rules with zero model calls. Live Gemini comparison remains pending because no key was available. The controlled fixtures below show the gains in specific patterns.

## Reproduce a run

From a checkout of Undertow with the release commit in Git history, create bare clones under an ignored local directory. Use Python 3.11+ and Git. The runner uses the worker from the released Git object and reads only committed source from the target repositories.

```sh
mkdir -p .undertow/eval-repos/pallets .undertow/eval-repos/encode
git clone --bare https://github.com/pallets/click.git .undertow/eval-repos/pallets/click.git
git clone --bare https://github.com/encode/httpx.git .undertow/eval-repos/encode/httpx.git

python3 evaluation/run.py run --repos .undertow/eval-repos --output .undertow/eval-local
python3 evaluation/run.py score .undertow/eval-local/run.json
python3 evaluation/run.py score evaluation/baselines/v0.3.0-local.json
```

Each run requires a new output directory and retains the Markdown and JSON worker reports alongside `run.json`. The run record binds results to the case manifest, release commit, requested mode, actual mode and per-finding fingerprints. An unavailable comparison remains in the record and gives the run a nonzero exit status. To compare a future Undertow revision, pass its trusted full commit SHA with `--revision` and reuse the frozen manifest.

To measure an unreleased change, use `--working-tree` from its trusted checkout. This snapshots the worker once and records its SHA-256.

```sh
python3 evaluation/run.py run --repos .undertow/eval-repos --output .undertow/local-rerun --working-tree
python3 evaluation/run.py score .undertow/local-rerun/run.json
```

For a Gemini run, set `GEMINI_API_KEY` in your environment and add `--mode gemini` with a fresh output directory. The worker uses its configured default unless you pass `--model`. Gemini receives selected excerpts, names and filenames from the evaluated repositories. Compare matching case IDs and use each record's actual mode. The scorer excludes local fallback and comparisons with no model interpretation from Gemini denominators.

For private cases, keep the manifest, bare clones, reports and reviews in an ignored directory such as `.undertow/`. Pass `--manifest PATH` before the subcommand and lay out the clones as `owner/repository.git` under `--repos`. Local mode ignores any ambient Gemini key. Use Gemini only for source you are authorized to share with the provider.

## Label and adjudicate

1. Select cases before reading Undertow output. Use a fixed base/head pair and upstream tests, source or release notes to label material behavior. Record one ID per labeled outcome, the affected path, input, before/after outcomes and source evidence. Record `bug_fix`, `intentional_change`, `no_change` or `unverified` for the PR. Track runtime controls and exclude uncertain PRs from control counts. Set each outcome's `supported` flag from the frozen worker's documented patterns before running the benchmark.
2. Review each finding against the cited source and its example. For `score --adjudications PATH`, use a JSON object keyed by case ID, then by finding fingerprint from `run.json` in report order. Each value has a `verdict` of `correct`, `incorrect` or `unverified`. A correct finding also has `changes` containing the IDs it establishes and `concrete` as a Boolean for a concrete input with accurate outcomes on both revisions. Use an empty `changes` list for a verified finding outside the original labels. Missing verdicts remain unverified.
3. Report precision over adjudicated findings and signal quality over the same denominator. Supported recall counts each matched supported outcome once. Recall and incorrect-finding rates use labeled PRs whose findings have all been adjudicated. Score an empty report as zero findings. Use the separate count of unavailable comparisons when interpreting results. Record actions only when a reviewer can link a finding to a test, fix, policy confirmation or release decision. Record model cost only from measured usage. Keep those observations with the run.

## Controlled fixtures and known limitations

The [regression suite](../tests/) builds deterministic two-revision repositories with Git. Run `python3 -m unittest discover -s tests -v`. The controlled cases cover a numeric boundary, a literal return change and added/removed HTTP routes, with quiet controls for comments and formatting or private-name changes. The suite also checks Markdown escaping, model request bounds and fallback, publication against current PR revisions, and the Action's source-only execution path. These checks use synthetic data and are separate from the historical scores.

The local rules detect the named and multiline thresholds and resolve the imported caller in the three fixtures below. The suite also covers statement reflow, equivalent integer boundaries, ignored lookalikes, constant shadowing and rebinding, and verified versus candidate calls. The controlled negative fixtures produced zero new findings or unrelated caller links. Two cases remain `expectedFailure` and CI will flag an unexpected pass.

| Case | v0.3.0 | Local rules with threshold and caller tracing |
| --- | --- | --- |
| Named threshold constant | Missed | `age_days = 30` changes from true to false, with links to the declaration and predicate. |
| Multiline threshold | Missed | Produces the same distinguishing input and links the full comparison on both revisions. |
| Unrelated same-name symbols | False caller association | The imported caller is verified and the unrelated caller is absent. |
| Configuration table | Expected failure | Table entries still lack a downstream witness. |
| Helper with a local assignment | Expected failure | Local assignment tracking still lacks a witness. |

Results are inferred from source. Coverage limits and skipped file types can also contribute to an empty report. The Action guide describes those bounds. [Business change review](../docs/change-review.md)
