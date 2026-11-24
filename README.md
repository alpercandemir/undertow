<p align="center">
  <img src="docs/assets/undertow-logo.png" alt="Undertow logo" width="160">
</p>

<h1 align="center">Undertow</h1>

<p align="center">
  <a href="https://github.com/alpercandemir/undertow/releases/latest"><img src="https://img.shields.io/github/v/release/alpercandemir/undertow?label=release&amp;color=2563eb" alt="Latest release"></a>
  <a href="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml"><img src="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI status"></a>
</p>

Undertow runs the same generated inputs on two Python revisions after CI passes. It reports an input that changes an observable outcome and turns it into a standalone pytest test once a reviewer chooses the expected result.

[Regression examples](#three-regressions-that-pass-the-existing-suite) · [Run on demand](#run-on-demand) · [Historical benchmark](#historical-benchmark)

## Three regressions that pass the existing suite

These runnable examples use synthetic data and deliberately introduced regressions. Each compares two Git commits with the same existing tests.

| What changed | Input Undertow found and what happened | Reproduce or inspect |
| --- | --- | --- |
| **A $40 refund disappears.** A webhook refactor deduplicates by payment and amount. | `[1, 1]` creates two separate one-item refunds on a $120 payment, with a retry of the first webhook in between. The base records both refunds. The candidate treats the second as another retry and records **$40 instead of $80**. | [Patch](evaluation/replay-examples/refunds/candidate.patch) · [Report](evaluation/replay-examples/results/refunds/report.json) · [New test](evaluation/replay-examples/results/refunds/regression.py) |
| **Three invoices are skipped after a restart.** An exporter saves the end of a batch as its checkpoint. | `1` lets the finance inbox acknowledge only the first of four invoices before the worker restarts. The base resumes at the second invoice. The candidate reports the same final checkpoint as the base, with **one invoice delivered instead of four**. | [Patch](evaluation/replay-examples/invoice-export/candidate.patch) · [Report](evaluation/replay-examples/results/invoice-export/report.json) · [New test](evaluation/replay-examples/results/invoice-export/regression.py) |
| **A ferry disappears an hour before departure.** The board starts filtering by port-local time. | `10` minutes after the board opens, the port clock reads 01:30 BST. The 01:30 GMT sailing is still an hour away. The base shows it and the candidate drops it from the board. | [Patch](evaluation/replay-examples/departure-board/candidate.patch) · [Report](evaluation/replay-examples/results/departure-board/report.json) · [New test](evaluation/replay-examples/results/departure-board/regression.py) |

All 13 existing tests passed on both sides. They covered webhook redelivery, full batch acknowledgement and ordinary departure dates. Each exported test passed on its base commit and was the sole failure on its candidate. [Recorded test results](evaluation/replay-examples/results/verification.json)

Each probe defines the workflow and records what a caller can observe. Undertow supplies the input, runs both revisions and repeats the distinguishing case on its own. We selected the base outcome as the contract for each generated test. [Refund probe](evaluation/replay-examples/refunds/probe.py) · [Export probe](evaluation/replay-examples/invoice-export/probe.py) · [Departure probe](evaluation/replay-examples/departure-board/probe.py)

Run all three locally with Git, Python 3.11+, pytest and IANA time-zone data. The runner creates the two commits for each scenario, verifies that their original tests pass, and checks the exported test against both.

```sh
python3 evaluation/replay-examples/run.py --output .undertow/replay-examples
```

## Run on demand

After your normal CI passes, choose a public API scenario and run a small Python probe on the base and candidate commits. Inspect the difference and export a pytest test for the outcome you want to keep. [Local commands and test export](docs/behavior-replay.md#probe-a-public-api-and-export-a-pytest-test)

The GitHub Actions example runs when a maintainer dispatches it with two commits and a reviewed probe. Results go to the run summary and artifact. The showcase replays the Click regression and exports the test in Actions. [Manual workflow](docs/examples/behavior-replay.yml) · [Runnable showcase](https://github.com/alpercandemir/undertow-showcase)

<a id="historical-benchmark-for-complementary-behavioral-coverage"></a>

## Historical benchmark

In merged HTTPX PR #3364, both revisions passed 1,425 existing tests in the local non-network run. A request with empty `params` kept an existing URL query on base and cleared it on head. Undertow reproduced the difference and exported a test for the reviewed new behavior. A later upstream PR added an equivalent assertion. [Original PR](https://github.com/encode/httpx/pull/3364) · [Trial results](evaluation/benchmark.md#what-the-probes-added) · [Upstream test](https://github.com/encode/httpx/pull/3440)

The runtime value trial found no eligible automatic target across 21 historical comparisons. Reused probes supplied five reproducible observations, including one compatibility effect absent from the original PR description and tests. Copilot's source review already described that effect. Undertow reproduced it and generated a runnable test. [Runtime value after passing tests](evaluation/benchmark.md#runtime-product-value-after-passing-tests) · [Product assessment](evaluation/benchmark.md#product-assessment)

The November 24 rerun used the Undertow v0.5.0 release worker on 20 historical Click and HTTPX PRs with 18 labeled behavior changes. Paired runtime probes reproduced the expected differences on all 16 behavior-changing PRs and on a separately scored Click stdin regression. Four controls preserved the behavior under review. [Benchmark and run records](evaluation/benchmark.md#v050-rerun) · [Runtime observations](evaluation/results/v0.5.0/runtime-observations.json)

The Click stdin trial used a reported regression to select the probe. Click's existing suites passed on the base, regression and fix. The generated test failed on the regression alone. A separate six-comparison Click option pilot observed no differences in the paths it exercised. [Click stdin trial](evaluation/click-replay.md) · [Option pilot](evaluation/click-options-pilot.md)

| Source review | PRs completed in requested mode | Labeled changes found across 20 PRs | Accurate examples |
| --- | ---: | ---: | ---: |
| Undertow + Gemini 3.8 Flash API (v0.5.0 default) | 0 / 20 in each of two passes | 0 / 18 in each pass | — |
| Undertow + Gemini 3.5 Flash-Lite API | 4 / 20 | 4 / 18 | 1 / 2 |
| Undertow + Gemini 3.1 Flash-Lite API | 1 / 20 first pass, 0 / 20 paced pass | 1 / 18 first pass, 0 / 18 paced pass | 1 / 1 first pass |
| Undertow local rules | 20 / 20 | 0 / 18 | — |
| GitHub Copilot CLI | 20 / 20 | 17 / 18 | 27 / 39 |

Eighteen PRs had reviewable source. All 36 reviewable attempts at the default Gemini model returned HTTP 429 or 503 and fell back to local rules. The Gemini findings that completed covered changes the new Copilot run also found. [API failures, scoring and costs](evaluation/benchmark.md#november-24-results)

### November 20 baseline

| Source review | PRs completed in requested mode | Labeled changes found across 20 PRs | Accurate examples |
| --- | ---: | ---: | ---: |
| Undertow + Gemini 3.1 Flash-Lite API | 11 / 20 | 8 / 18 | 1 / 10 |
| GitHub Copilot CLI | 20 / 20 | 15 / 18 | 22 / 32 |
| Undertow local rules | 20 / 20 | 0 / 18 | — |
| Union of Gemini and Copilot findings | — | **16 / 18** | — |

On the 11 PRs both model-backed reviews completed, Gemini found 8 of 11 labeled changes, Copilot found 9, and the union covered 10. Gemini supplied the additional Click #2604 finding. Decorating `my_sync_command` without a name changed the generated name from `my-sync-command` to `my-sync`. [Baseline results](evaluation/benchmark.md#measured-results-on-the-frozen-20-prs) · [Click report](evaluation/results/historical-gemini/click-2604/report.json)

[Per-PR v0.5.0 results](evaluation/benchmark.md#per-pr-november-24-results) · [Frozen case manifest](evaluation/historical.json)

<details>
<summary>Recompute the v0.5.0 benchmark scores</summary>

Run from the repository root with Python 3.11+. The checked-in runs can be scored without provider credentials.

```sh
results=evaluation/results/v0.5.0
python3 evaluation/run.py score "$results/local/historical/run.json"
python3 evaluation/run.py score "$results/gemini-3.1-flash-lite-first/historical/run.json" \
  --adjudications "$results/gemini-3.1-flash-lite-first/historical/adjudications.json"
python3 evaluation/run.py score "$results/paced-gemini-3.5-flash-lite/historical/run.json" \
  --adjudications "$results/paced-gemini-3.5-flash-lite/historical/adjudications.json"
python3 evaluation/run.py score "$results/copilot/historical/run.json" \
  --adjudications "$results/copilot/historical/adjudications.json"
```

The detailed report includes both Gemini passes and the November 20 baseline. [Detailed results](evaluation/benchmark.md) · [Rerun the benchmark](evaluation/competitive-pilot.md)

</details>
