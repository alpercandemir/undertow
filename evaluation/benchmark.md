# Historical PR benchmark

Compare the [November 24 v0.5.0 benchmark](#v050-rerun) with the [November 20 baseline](#measured-results-on-the-frozen-20-prs). Both use the same 20 merged Click and HTTPX PRs and 18 frozen behavior labels.

## v0.5.0 rerun

The [release worker](https://github.com/alpercandemir/undertow/blob/7d6c9b09705b439d7a246ca5e93b38a29dcf2e8a/actions/change-review/review_engine.py) ran against the 20 pinned PRs and the supplemental Click stdin regression. GitHub-hosted Ubuntu 24.04 ran local rules and the Gemini Developer API calls with the existing repository secret. The first pass selected the v0.5.0 default (`gemini-3.8-flash`) and the historical model (`gemini-3.1-flash-lite`). The second pass added `gemini-3.5-flash-lite`, waited 12 seconds between cases, and recorded the API response statuses and token counts outside the release worker. Worker timing excludes the wait. [First pass](https://github.com/alpercandemir/undertow/actions/runs/45436689272) · [Paced pass](https://github.com/alpercandemir/undertow/actions/runs/45437656813) · [Paced workflow and observer](https://github.com/alpercandemir/undertow/tree/3eeede21f571802091a374e8ee8451cca07c4555)

Copilot CLI 1.0.114 used the same fixed prompt and read-only source tools as the November 20 baseline. Explicit Luna selection failed, so the run used account routing. Copilot served `mai-code-2-flash` for 14 PRs and `gpt-6-luna` for six. The host ran three sessions concurrently, and timing excludes source export. One valid response, HTTPX #3364, began its JSON array on the same line as a progress message. The updated parser recovered it from the saved response. [Copilot run](results/v0.5.0/copilot/historical/run.json) · [Raw response](results/v0.5.0/copilot/historical/httpx-3364/response.txt)

### November 24 results

Coverage across all attempts uses the 18 frozen labels. The completed-case denominator uses the labels from PRs reviewed in the requested mode. Codex provisionally graded every finding using the pinned source, upstream tests and the paired observations. Correctness covers the material behavior claim. Example accuracy requires the same concrete input and compatible outcomes on both revisions. A dash indicates an empty quality denominator. [Run records and adjudications](results/v0.5.0/)

| Configuration | Completed PRs | Labels found across all attempts | Labels found on completed PRs | Correct findings | Accurate examples | Incorrect findings per scored PR / control |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Undertow local | 20 / 20 | 0 / 18 | 0 / 18 | — | — | 0 / 20 · 0 / 4 |
| Gemini 3.8 Flash, first pass | 0 / 20 | 0 / 18 | — | — | — | — |
| Gemini 3.8 Flash, paced | 0 / 20 | 0 / 18 | — | — | — | — |
| Gemini 3.1 Flash-Lite, first pass | 1 / 20 | 1 / 18 | 1 / 1 | 1 / 1 | 1 / 1 | 0 / 1 · — |
| Gemini 3.1 Flash-Lite, paced | 0 / 20 | 0 / 18 | — | — | — | — |
| Gemini 3.5 Flash-Lite, paced | 4 / 20 | 4 / 18 | 4 / 5 | 2 / 2 | 1 / 2 | 0 / 4 · 0 / 1 |
| GitHub Copilot CLI | 20 / 20 | 17 / 18 | 17 / 18 | 35 / 39 | 27 / 39 | 4 / 20 · 1 / 4 |

Gemini 3.5 Flash-Lite found the Click #2680 callback change and the three HTTPX #3367 serialization changes. The serialization example was inaccurate because it removed the `NaN` field from the input to show a successful head body. On the same four PRs, Copilot found 5 of 5 labels, including the nested Click completion change that Gemini missed. The 3.1 Flash-Lite result reproduced the Click #2604 command rename, which the new Copilot run also reported. The union of the November 24 findings covered 17 of 18 labels. The unreported label was the final empty text chunk in HTTPX #2998. Copilot found the intermediate empty chunks from split UTF-8 instead. [Click callback report](results/v0.5.0/paced-gemini-3.5-flash-lite/historical/click-2680/report.json) · [HTTPX serialization report](results/v0.5.0/paced-gemini-3.5-flash-lite/historical/httpx-3367/report.json) · [Click rename report](results/v0.5.0/gemini-3.1-flash-lite-first/historical/click-2604/report.json)

In the separate Click #2934 regression, the paired run printed `test` on both revisions and changed the exit code from 0 to 1 with an `Aborted!` message on head. Copilot reported the labeled abort. Local rules produced zero findings. All five Gemini attempts fell back after HTTP errors. [Supplemental scores](results/v0.5.0/copilot/click-stdin/adjudications.json) · [Runtime observations](results/v0.5.0/runtime-observations.json)

### API availability, timing and cost

Two PRs had no reviewable source regions in every Gemini pass. They made zero model calls. The table counts the final reason for each fallback on the other 18 PRs, with one reason per PR.

| Gemini API pass | HTTP 429 | HTTP 503 | Request-window failures | Report validation | Median worker time across all attempts | Median when completed in Gemini mode |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 3.8 Flash, first pass | 5 | 13 | 0 | 0 | 0.540 s | — |
| 3.8 Flash, paced | 8 | 10 | 0 | 0 | 0.440 s | — |
| 3.1 Flash-Lite, first pass | 0 | 14 | 3 | 0 | 0.916 s | 28.218 s |
| 3.1 Flash-Lite, paced | 0 | 17 | 1 | 0 | 2.721 s | — |
| 3.5 Flash-Lite, paced | 0 | 11 | 1 | 2 | 3.324 s | 4.277 s |

The default 3.8 Flash run measured API availability and fallback behavior, with zero completed reviews to grade. The 3.5 Flash-Lite pass received six HTTP 200 responses. The worker rejected two of those reports because a finding gave identical example outcomes. The other four reports included two findings and two empty reviews. [Paced 3.8 response metadata](results/v0.5.0/paced-gemini-3.8-flash/historical/provider-usage.json) · [3.5 response metadata](results/v0.5.0/paced-gemini-3.5-flash-lite/historical/provider-usage.json)

The metered 3.5 responses used 17,669 input tokens and 1,378 output tokens. Those tokens correspond to **$0.00875** at the November 24 standard paid rates of $0.30 and $2.50 per million. The estimate includes the rejected responses. Usage for the first pass and for requests without token metadata is unknown. Copilot recorded **$0.1130** in credit-equivalent usage across 20 PRs and a 57.295 s median. Local source review had zero model calls and a 0.114 s median on the Actions runner. These are worker timings and usage estimates. The runs did not collect provider invoices or runner charges. [Gemini pricing](https://aistudio.google.com/docs/pricing#gemini-3.5-flash-lite) · [Copilot usage and billing](https://docs.github.com/en/copilot/how-tos/copilot-sdk/features/usage-and-billing)

### Runtime cross-check

The probes ran twice per revision in fresh Python 3.12 processes on 19 cases, including the supplemental regression. They reproduced the expected differences on all 16 behavior-changing corpus PRs and the supplement. The annotation and private-helper controls matched at runtime. The docs-only and test-only controls had identical library Git trees. The timeout probe used a mock transport to observe the client timeout extension. The import probe inspected `sys.modules` after import and client construction. [Paired observations](results/v0.5.0/runtime-observations.json) · [Probe code](runtime_probes.py)

The new observations establish the selected scenarios and provide evidence for provisional, tool-visible adjudication. The corpus still represents two public Python libraries. Each model run samples a single response path per PR, with two separate passes for 3.1 and 3.8. The run includes Undertow's source selection and output validation, and the API reliability figures describe this runner and execution window.

Run the additional probes after preparing the [bare repositories](competitive-pilot.md#prepare-the-repositories).

```sh
trial=.undertow/historical-runtime
uv venv --python 3.12 "$trial/venv"
uv pip install --python "$trial/venv/bin/python" \
  'httpcore==1.0.9' 'anyio==4.9.0' 'idna==3.10' 'certifi==2025.4.26' \
  'sniffio==1.3.1' 'socksio==1.0.0'
"$trial/venv/bin/python" evaluation/runtime_trial.py --historical \
  --repos .undertow/eval-repos --output "$trial/observations"
```

### Per-PR November 24 results

The table shows labeled detections for Copilot and the Gemini pass with the most completed PRs. The first 3.1 Flash-Lite pass completed only Click #2604 and found its one label. The other three Gemini passes completed zero corpus PRs.

| PR | Copilot CLI | Gemini 3.5 Flash-Lite |
| --- | ---: | ---: |
| [Click #2365](https://github.com/pallets/click/pull/2365) | 1 / 1 | HTTP 503 · fallback |
| [Click #2604](https://github.com/pallets/click/pull/2604) | 1 / 1 | HTTP 503 · fallback |
| [Click #2680](https://github.com/pallets/click/pull/2680) | 1 / 1 | 1 / 1 |
| [Click #2846](https://github.com/pallets/click/pull/2846) | 1 / 1 | HTTP 503 · fallback |
| [Click #2930](https://github.com/pallets/click/pull/2930) | 1 / 1 | HTTP 503 · fallback |
| [Click #2935](https://github.com/pallets/click/pull/2935) | 1 / 1 | 0 / 1 |
| [Click #2972](https://github.com/pallets/click/pull/2972) | 1 / 1 | HTTP 503 · fallback |
| [Click #2990](https://github.com/pallets/click/pull/2990) | Control · 1 finding | HTTP 503 · fallback |
| [Click #3004](https://github.com/pallets/click/pull/3004) | 1 / 1 | HTTP 503 · fallback |
| [Click #3009](https://github.com/pallets/click/pull/3009) | Control · 0 findings | No source regions |
| [HTTPX #2998](https://github.com/encode/httpx/pull/2998) | 0 / 1 | HTTP 503 · fallback |
| [HTTPX #3116](https://github.com/encode/httpx/pull/3116) | 1 / 1 | HTTP 503 · fallback |
| [HTTPX #3178](https://github.com/encode/httpx/pull/3178) | 1 / 1 | HTTP 503 · fallback |
| [HTTPX #3364](https://github.com/encode/httpx/pull/3364) | 1 / 1 | Report validation · fallback |
| [HTTPX #3367](https://github.com/encode/httpx/pull/3367) | 3 / 3 | 3 / 3 |
| [HTTPX #3373](https://github.com/encode/httpx/pull/3373) | 1 / 1 | HTTP 503 · fallback |
| [HTTPX #3375](https://github.com/encode/httpx/pull/3375) | Control · 0 findings | No source regions |
| [HTTPX #3377](https://github.com/encode/httpx/pull/3377) | 1 / 1 | Request window · fallback |
| [HTTPX #3378](https://github.com/encode/httpx/pull/3378) | 1 / 1 | Report validation · fallback |
| [HTTPX #3387](https://github.com/encode/httpx/pull/3387) | Control · 0 findings | Control · 0 findings |

## Dataset and comparison

The following records describe the November 20 source review.

- The frozen corpus contains 20 merged public Python library PRs, 10 each from Pallets Click and Encode HTTPX. It contains seven intentional changes to public behavior, nine fixes for reported bugs, and four runtime controls covering an annotation change, docs, tests, and a code-moving private-helper refactor. It includes library policy decisions such as query replacement, allowed proxy schemes, and JSON serialization. The 18 outcomes are retrospective source and test labels. The November 24 runtime cross-check covers the frozen scenarios. The selection favors cases with clear upstream evidence and has little coverage of Undertow's numeric rules or application-specific policies. [Frozen case metadata, outcomes, and evidence](historical.json) · [Baseline methodology](README.md)
- Click #2934 supplies a separately scored regression-introducing case. Its prompt EOF change also broke ordinary stdin iteration through `CliRunner`, as reported in #2939 and confirmed by the subsequent fix. The case and regression label were recorded before running either reviewer on it. The original manifest and `supported` flags remained unchanged. All 21 pinned heads were verified against GitHub's merged-PR metadata and their bases against the first Git parent. [Supplemental manifest](click-stdin-regression.json) · [Regression report](https://github.com/pallets/click/issues/2939) · [Upstream fix](https://github.com/pallets/click/pull/2940)
- All three configurations used the same pinned base and head revisions. Copilot CLI `1.0.114` used a fixed source-review prompt and on-demand access to the exported trees and full diff. Undertow chose bounded source regions and references. All reviews ran without original PR discussions, later commits, or repository test execution. This evaluates the prompted CLI workflow. Its account default served `mai-code-2-flash` in 11 corpus runs and `gpt-6-luna` in nine. The supplement also used Luna. Explicitly selecting `mai-code-2-flash` failed before review. One run per case with mixed model routing limits repeatability, and public code may be familiar from model training. [Reproduction protocol](competitive-pilot.md)

- The Gemini run used the exact Undertow commit `5d508b349e7985a67ff110654f6af25b0d90d2b9` and pinned `gemini-3.1-flash-lite` as the requested model. The GitHub runner executed the benchmark with a repository Actions secret. The worker selected changed excerpts and references and could make a second model call for bounded context. [GitHub Actions run](https://github.com/alpercandemir/undertow/actions/runs/44787294354) · [Run record](results/historical-gemini/run.json)

## Measured results on the frozen 20 PRs

Codex provisionally assessed the Copilot and Gemini findings against the pinned revisions and upstream evidence with tool identity visible. A valid core behavior change and the accuracy of its example are graded independently. Static typing alone was outside the runtime scope. [Copilot adjudications](results/historical-copilot-adjudications.json) · [Gemini adjudications](results/historical-gemini/adjudications.json)

| Metric | Undertow local | GitHub Copilot CLI | Undertow with Gemini |
| --- | ---: | ---: | ---: |
| PRs completed in requested mode | 20/20 | 20/20 | 11/20 |
| Labeled outcomes found across all attempted PRs | 0/18 | 15/18 | 8/18 |
| Recall among completed cases in requested mode | 0% (0/18) | 83.3% (15/18) | 72.7% (8/11) |
| Findings in requested mode | 0 | 33 | 12 |
| Behavioral precision | Undefined (0/0) | 87.9% (29/33) | 50.0% (6/12) |
| Accurate before/after examples | Undefined (0/0) | 68.8% (22/32) | 10.0% (1/10) |
| Incorrect findings per scored PR | 0.00 (0/20) | 0.20 (4/20) | 0.55 (6/11) |
| Incorrect findings per scored control | 0.00 (0/4) | 0.50 (2/4) | 1.00 (2/2) |
| Median worker time in requested mode | 0.240 s | 52.437 s | 3.774 s |
| Provider cost | $0 | $0.1292 estimated total | Unmeasured |

Seven of the 18 PRs with reviewable source fell back to local rules in the Gemini run. Two calls timed out. Five reports or context requests failed validation, including two out-of-scope symbol requests, two invalid reviews, and one finding without valid source references. The other two PRs contained no reviewable source regions and made no model calls. The Gemini quality scores cover the 11 PRs completed in Gemini mode. The 8/18 result measures what the full Gemini run surfaced. The nine local reports contained zero findings. [Local run](results/historical-local.json) · [Copilot run](results/historical-copilot.json) · [Gemini reports](results/historical-gemini/) · [Metric definitions](competitive-pilot.md#blind-review-and-scoring)

### Comparison on the same 11 PRs

The two independent reviews identified seven of the same labeled outcomes. Undertow with Gemini alone reported the Click command rename. Copilot CLI alone reported two labeled changes and three further behavior changes outside the frozen labels. Both missed one labeled change. Combined coverage counts the union of labeled outcome IDs per PR across the saved reviews, giving 10/11 on these PRs and 16/18 across the full corpus. [Compare the saved runs](competitive-pilot.md#blind-review-and-scoring)

On these same PRs, Undertow with Gemini had 6 correct findings out of 12 and 1 accurate before/after example out of 10. Copilot CLI had 14 correct findings out of 18 and 12 accurate examples out of 18. The combined count measures detection coverage. Assessing the quality of a joint review would require a separate evaluation.

On Click #2604, Undertow with Gemini connected the suffix rule to a concrete user-facing command. Decorating `my_sync_command` without an explicit name produced `my-sync-command` before and `my-sync` after. Scripts using the generated name may require an update. On HTTPX #3367, the report correctly identified compact JSON, UTF-8 output and rejection of non-finite numbers in one finding. The precise UTF-8 example still showed a space the new serializer removes. [Click report](results/historical-gemini/click-2604/report.json) · [HTTPX report](results/historical-gemini/httpx-3367/report.json)

Timing is wall time at each run host. Undertow includes startup, Git reads, report writes and model calls where applicable. The Gemini worker ran on GitHub-hosted Linux. The other measurements came from a local host. Copilot includes its CLI session and provider/tool calls, with three sessions in flight and source exports outside the timer. Gemini token usage was not captured. The 20 local sessions consumed 5.177 total worker-seconds. To estimate metered local compute, multiply `5.177 / 3600` by the runner's hourly rate in dollars. No runner invoice was used. Copilot cost uses the 20 sessions' recorded usage converted from nano-AI units to credits at an estimated one billion nano-units per credit and $0.01 per credit. This gives a budget equivalent, with account billing, subscription, and concurrency effects outside the estimate. Access checks are excluded. [GitHub usage metrics](https://docs.github.com/en/copilot/how-tos/copilot-sdk/features/usage-and-billing) · [GitHub Copilot billing](https://docs.github.com/en/billing/concepts/product-billing/github-copilot-billing)

In the supplemental Click regression, Gemini identified the underlying iterator change from `StopIteration` to `EOFError`. Its report stopped at the wrapper, leaving the labeled command-abort outcome unestablished. Copilot tied the exception to the command abort and also reported the prompt-default change. Local rules emitted zero findings. [Gemini report](results/click-stdin-gemini/click-2934/report.json) · [Gemini adjudications](results/click-stdin-gemini/adjudications.json) · [Copilot adjudications](results/click-stdin-copilot-adjudications.json)

## Strengths and gaps

- **Controlled coverage.** The local rules report named thresholds, multiline comparisons, and caller bindings on controlled Git fixtures. The release baseline, its rerun, and the worker with threshold and caller tracing all produced zero findings on the historical corpus. [Controlled checks](../tests/test_review_engine.py) · [Release baseline](baselines/v0.3.0-local.json) · [Release rerun](results/historical-release-rerun.json) · [Threshold and caller run](baselines/thresholds-and-callers-local.json)
- **Detection gaps.** The local rules missed public behavior caused by helper dispatch, serializer arguments, state and exception propagation, and import timing. Examples include Click's cleanup callbacks on context exit, HTTPX's rejection of non-finite JSON, and the supplemental stdin regression. Copilot missed the labeled Click command rename, which Undertow with Gemini identified. Both model-backed configurations missed the labeled final empty string from HTTPX text streaming. The Gemini run fell back on the deferred-import PR. [Click #2604](https://github.com/pallets/click/pull/2604) · [HTTPX #2998](https://github.com/encode/httpx/pull/2998) · [HTTPX #3377](https://github.com/encode/httpx/pull/3377)
- **False positives and witness errors.** Undertow's local rules produced zero findings on the corpus. Copilot invented an empty generated name by overlooking the separator check in #2604 and claimed a chained completion difference contradicted by the unchanged test in #2935. Its other two false positives concern static typing on the annotation control. The live run also shows why witness accuracy merits its own metric. Copilot found the standalone timeout change but used relative URLs in its examples, which cannot reach the slow endpoint through the default HTTP transport. [Copilot adjudications](results/historical-copilot-adjudications.json) · [Click #2935](https://github.com/pallets/click/pull/2935) · [HTTPX #3116](https://github.com/encode/httpx/pull/3116)

## What the comparison measures

Undertow supplies pinned comparison inputs, chooses source excerpts and call references, checks the output schema and citation IDs, and publishes the same report format in a PR comment or artifact. Each accepted finding includes an example or precondition, a review question, and an evidence label. The review of JSON output illustrates the limits of those checks. A finding can cite real changed code and still give an inaccurate example.

The scores describe complete review configurations on two Python libraries. Different models, context budgets, and review pipelines make it impossible to assign the measured difference to Undertow's harness alone. An experiment with the same model on the same cases could measure the effect of adding Undertow's source selection, reference tracing, and report checks. No independent maintainer assessments or changes to tests, fixes, or releases attributable to these reviews were recorded. [Reproduction protocol](competitive-pilot.md) · [Assessment criteria](maintainer-assessment.md)

## Per-PR results

Each fraction shows how many of the labeled changes the reviewer identified. All 18 labeled changes fell outside the declared patterns of the local rules. A control has no labeled change to detect. Fallback and cases with no source regions are shown separately for the Gemini run.

| PR | Behavior covered | Known changes | Undertow local | Copilot CLI | Undertow + Gemini |
| --- | --- | ---: | ---: | ---: | ---: |
| [Click #2365](https://github.com/pallets/click/pull/2365) | Respect the choice-display setting in help. | 1 | 0 / 1 | 1 / 1 | Local fallback |
| [Click #2604](https://github.com/pallets/click/pull/2604) | Trim decorator suffixes from generated command names. | 1 | 0 / 1 | 0 / 1 | 1 / 1 |
| [Click #2680](https://github.com/pallets/click/pull/2680) | Run close callbacks when explicitly exiting a context. | 1 | 0 / 1 | 1 / 1 | 1 / 1 |
| [Click #2846](https://github.com/pallets/click/pull/2846) | Escape completion values containing colons when Zsh emits a description. | 1 | 0 / 1 | 1 / 1 | 1 / 1 |
| [Click #2930](https://github.com/pallets/click/pull/2930) | Set implicit flag values when a type is supplied. | 1 | 0 / 1 | 1 / 1 | 0 / 1 |
| [Click #2935](https://github.com/pallets/click/pull/2935) | Use the current nested group when resolving completion. | 1 | 0 / 1 | 1 / 1 | 0 / 1 |
| [Click #2972](https://github.com/pallets/click/pull/2972) | Omit the environment hint when no variable is configured. | 1 | 0 / 1 | 1 / 1 | 1 / 1 |
| [Click #2990](https://github.com/pallets/click/pull/2990) | Type annotations expand to match existing exception formatting. The runtime formatting path is unchanged. | 0 | Control | Control | Control |
| [Click #3004](https://github.com/pallets/click/pull/3004) | Render the Enum member name as the displayed option default. | 1 | 0 / 1 | 1 / 1 | Local fallback |
| [Click #3009](https://github.com/pallets/click/pull/3009) | Changelog entry only. The referenced StreamMixer fix landed in a separate PR. | 0 | Control | Control | No source regions |
| [HTTPX #2998](https://github.com/encode/httpx/pull/2998) | Drop the spurious final empty string from text streaming. | 1 | 0 / 1 | 0 / 1 | 0 / 1 |
| [HTTPX #3116](https://github.com/encode/httpx/pull/3116) | Apply the client timeout to manually constructed requests. | 1 | 0 / 1 | 1 / 1 | Local fallback |
| [HTTPX #3178](https://github.com/encode/httpx/pull/3178) | Accept the socks5h proxy scheme. | 1 | 0 / 1 | 1 / 1 | 1 / 1 |
| [HTTPX #3364](https://github.com/encode/httpx/pull/3364) | Explicit request params replace the query in the URL. | 1 | 0 / 1 | 1 / 1 | Local fallback |
| [HTTPX #3367](https://github.com/encode/httpx/pull/3367) | Change JSON byte serialization and reject non-finite numbers. | 3 | 0 / 3 | 3 / 3 | 3 / 3 |
| [HTTPX #3373](https://github.com/encode/httpx/pull/3373) | Change escaping for query parameters passed as key-value pairs. | 1 | 0 / 1 | 1 / 1 | Local fallback |
| [HTTPX #3375](https://github.com/encode/httpx/pull/3375) | Removes a test case. The library source is unchanged. | 0 | Control | Control | No source regions |
| [HTTPX #3377](https://github.com/encode/httpx/pull/3377) | Load optional runtime modules at their point of use. | 1 | 0 / 1 | 0 / 1 | Local fallback |
| [HTTPX #3378](https://github.com/encode/httpx/pull/3378) | Narrow the Request method parameter to strings. | 1 | 0 / 1 | 1 / 1 | Local fallback |
| [HTTPX #3387](https://github.com/encode/httpx/pull/3387) | Moves internal utility functions and their tests. Public header and response processing retain equivalent calls. | 0 | Control | Control | Control |

[Per-case inputs and expected outcomes](historical.json) · [Gemini reports and assessments](results/historical-gemini/) · [Copilot assessments](results/historical-copilot-adjudications.json)
