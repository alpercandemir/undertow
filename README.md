<p align="center">
  <img src="docs/assets/undertow-logo.png" alt="Undertow logo" width="160">
</p>

<h1 align="center">Undertow</h1>

<p align="center">
  <a href="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml"><img src="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI status"></a>
</p>

Undertow shows Python maintainers what a pull request changes for callers. Write a small function that exercises your API, run it against two Git revisions, and choose which outcome your pytest suite should keep.

<a id="what-changed-in-httpx"></a>

## 1,425 tests passed. Where did the query string go?

HTTPX refactored request construction. In a retrospective run, **1,425 existing tests passed on each commit** with network tests excluded. Now give both versions the same URL and an empty dictionary of query parameters.

```python
httpx.Request("GET", "https://example.invalid/?a=1", params={})
```

| What a caller sees | Before the refactor | After the refactor |
| --- | --- | --- |
| URL on the request | `https://example.invalid/?a=1` | `https://example.invalid/` |

Should an empty dictionary preserve the existing query or clear it? We chose to keep the new URL. Undertow exported a test and ran it on both commits. It **failed before the refactor and passed after it**. It also passes on the later commit where HTTPX added its own assertion for that behavior. [Exported pytest test](evaluation/results/httpx-query-params/test_undertow_regression.py) · [Verification on both commits](evaluation/results/httpx-query-params/verification.json) · [Original refactor](https://github.com/encode/httpx/pull/3364) · [Upstream test](https://github.com/encode/httpx/pull/3440)

Here is the entire probe for this walkthrough. The author picks the cases that matter. Undertow supplies the two checkouts, repeats the observations, and shows the first difference that also reproduces on its own. [Runnable probe](evaluation/probes/httpx_query_params.py)

```python
import httpx

CASES = {
    "Use the URL as supplied": None,
    "Empty query parameters": {},
    "Supply a replacement query": {"page": "2"},
}

def probe(params):
    request = httpx.Request("GET", "https://example.invalid/?a=1", params=params)
    return str(request.url)
```

<details>
<summary><strong>Run the HTTPX replay and keep the test</strong></summary>

From the Undertow checkout on Linux or macOS, use Git and a current Python 3.11+. This downloads HTTPX’s history and installs the dependencies for the example in a temporary environment.

```sh
demo=$(mktemp -d)
git clone --quiet --bare https://github.com/encode/httpx.git "$demo/httpx.git"
python3 -m venv "$demo/venv"
"$demo/venv/bin/python" -m pip install -q 'httpcore==1.0.9' 'anyio==4.9.0' 'pytest==8.3.5'
"$demo/venv/bin/python" -IB actions/behavior-replay/review.py \
  --repo "$demo/httpx.git" --against 6f9b50990dad9645a6f9dc0e1eb90af0a6474c3b \
  --head 3f76571d3429497c4ee4ecf5d8bc034b6f765957 \
  --probe evaluation/probes/httpx_query_params.py --probe-module httpx._models \
  --expect head --output "$demo/review"
cat "$demo/review/test_undertow_regression.py"
```

The reviewer sees the named input, both URLs, and the result of running the exported test.

```text
Case: Empty query parameters
Input: probe({})
base: {"returned": "https://example.invalid/?a=1"}
head: {"returned": "https://example.invalid/"}
pytest: base fail · head pass
```

</details>

<a id="which-behavior-should-the-test-keep"></a>
<a id="three-regressions-that-pass-the-existing-suite"></a>

## Run the examples

**The last unit is reserved. Is the product sold out?** In the stock-feed example, the old API returns `{"available": 0}`. A refactor returns `{"available": false}`. All four existing tests pass on both commits. Python accepts `False == 0`, and the listing test supplies its own numeric response. Connect the real feed to the listing and a buyer sees **“Sold out” change to “Check availability.”** Keeping the base outcome catches the regression. [Existing tests](evaluation/replay-examples/stock-feed/source/existing_tests.py) · [Probe](evaluation/replay-examples/stock-feed/probe.py) · [Exported test](evaluation/replay-examples/results/stock-feed/regression.py)

From the repository root, run this synthetic example with Git, Python 3.11+ and pytest. It creates both commits, runs their existing tests, and checks the exported assertion on each.

```sh
examples=$(mktemp -d)
python3 evaluation/replay-examples/run.py --case stock-feed --output "$examples/stock"
```

| Another review decision | What changed for the caller | Which outcome to keep |
| --- | --- | --- |
| Two separate $40 refunds have the same amount. A retry arrives between them. | The customer gets **$80 → $40**. The refactor treats the second refund as a retry. | Keep **base**. [Refund example](evaluation/replay-examples/README.md) |
| Three groups share one $10 booking credit. | The discounts total **$9.99 → $10.00**. The fix assigns the remaining cent to the first group. | Keep **head**. [Exported credit test](evaluation/replay-examples/results/credit-split/regression.py) |

Those examples use deliberately constructed changes. For a real regression, a Click command read one line from stdin and printed it on both revisions. On the affected revision it then printed **Aborted!** and exited with status 1, while 738 existing tests passed. A probe written from the known upstream issue produced a test that passed before the bug, failed with it, and passed after the fix. [Click replay and test results](evaluation/click-replay.md) · [Manual Click demo](https://github.com/alpercandemir/undertow-showcase/actions/workflows/behavior-replay.yml)

To run all five synthetic examples, omit `--case`, use a fresh output directory, and install IANA time-zone data for the ferry case. [Results for all five](evaluation/replay-examples/results/verification.json)

<a id="run-on-demand"></a>

## Use it on your PR

The best time to use replay is when you know which caller workflow matters and still have a question about what changed. In an environment with pytest and your project’s dependencies, point Undertow at a trusted repository and a probe. Supply the candidate branch and its target branch. Undertow resolves their merge base.

```sh
python3 -IB actions/behavior-replay/review.py \
  --repo /path/to/project --against main --head my-feature \
  --probe path/to/probe.py --probe-module myproject.api --expect ask
```

Choose `base`, `head`, or `skip` after reading the two outcomes. If you choose a side, Undertow saves the test only after pytest passes on that revision and fails at the exported assertion on the other. Copy that file into your suite. The test runs with pytest and your project’s dependencies. [Local guide and input formats](docs/behavior-replay.md) · [Manual GitHub Actions workflow](docs/examples/behavior-replay.yml)

<a id="historical-benchmark-for-complementary-behavioral-coverage"></a>
<a id="historical-benchmark"></a>

## How often did replay add something?

Across 21 historical Click and HTTPX comparisons, automatic discovery found **zero eligible targets**. Four reusable probes reproduced differences in **five comparisons**. In **one**, the HTTPX refactor above, replay showed a material effect missing from the original PR description and tests. The other four confirmed behavior already described or tested. Whether the time spent on a probe pays off in live reviews is an open question. [Benchmark and case-by-case results](evaluation/benchmark.md#what-the-probes-added) · [External maintainer pilot](evaluation/maintainer-replay-pilot.md) · [Undertow’s own pilot](evaluation/pytest-export-pilot.md)
