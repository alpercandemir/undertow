<p align="center">
  <img src="docs/assets/undertow-logo.png" alt="Undertow logo" width="160">
</p>

<h1 align="center">Undertow</h1>

<p align="center">
  <a href="https://github.com/alpercandemir/undertow/releases/latest"><img src="https://img.shields.io/github/v/release/alpercandemir/undertow?label=release&amp;color=2563eb" alt="Latest release"></a>
  <a href="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml"><img src="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI status"></a>
</p>

Undertow looks for reproducible behavior differences after CI passes. It generates inputs, executes the same scenario on two Git revisions, and gives reviewers the observed results. A reviewer can turn a confirmed finding into a standalone pytest regression test.

[Real regression](#a-stdin-regression-that-clicks-tests-missed) · [Run after CI](#run-after-ci) · [Historical benchmark](#historical-benchmark)

## A stdin regression that Click's tests missed

Click #2934 changed how `CliRunner` handles end of input. A later issue reported that a command reading standard input as a file started aborting. We reran the existing pytest suites on the base, PR and fixed commits. All three passed locally. [Original PR](https://github.com/pallets/click/pull/2934) · [Bug report](https://github.com/pallets/click/issues/2939) · [Upstream fix](https://github.com/pallets/click/pull/2940)

| Check | Before the PR | With the PR | Upstream fix |
| --- | --- | --- | --- |
| Each revision's existing pytest suite | 737 passed | 738 passed | 1,284 passed |
| Undertow's generated input | Reads one line, exits 0 | Reads the line, prints `Aborted!`, exits 1 | Reads the line, exits 0 |
| Generated pytest regression test | Passes | Fails | Passes |

A small probe sets up the public CLI call and records output and exit code. This retrospective trial used the bug report to choose the scenario. Undertow generated the input `[1]` as one stdin line, checked that the difference reproduced, and exported the test after we selected the expected result. [Probe](evaluation/probes/click_stdin.py) · [Reproduce the comparison](evaluation/click-replay.md) · [Generated test](evaluation/results/click-probe/test_undertow_stdin.py)

## Run after CI

Add replay after your required test job. The example keeps observations in Actions and lets a maintainer request a PR comment after inspecting the evidence. Use your ordinary test suite and dependencies. [Workflow](docs/examples/behavior-replay.yml) · [Replay coverage and test export](docs/behavior-replay.md)

The workflow pins the released direct-function replay. The public API probe and test export run from this checkout. For a broader source-only view of supported policies, routes and callers, use the [source-review summary](docs/change-review.md).

<a id="historical-benchmark-for-complementary-behavioral-coverage"></a>

## Historical benchmark

The November 24 rerun used the Undertow v0.5.0 release worker on 20 historical Click and HTTPX PRs with 18 labeled behavior changes. Paired runtime probes reproduced the expected differences on all 16 behavior-changing PRs and on a separately scored Click stdin regression. Four controls preserved the behavior under review. [Benchmark and run records](evaluation/benchmark.md#v050-rerun) · [Runtime observations](evaluation/results/v0.5.0/runtime-observations.json)

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
