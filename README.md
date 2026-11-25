<p align="center">
  <img src="docs/assets/undertow-logo.png" alt="Undertow logo" width="160">
</p>

<h1 align="center">Undertow</h1>

<p align="center">
  <a href="https://github.com/alpercandemir/undertow/releases/latest"><img src="https://img.shields.io/github/v/release/alpercandemir/undertow?label=release&amp;color=2563eb" alt="Latest release"></a>
  <a href="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml"><img src="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI status"></a>
</p>

**CI passed. Did this change what callers see?** Give Undertow a small Python probe for a workflow you care about. It tries the same inputs on both commits. When it finds a reproducible difference, you choose which outcome to keep and export a standalone pytest test for it.

[Worked examples](#which-behavior-should-the-test-keep) · [HTTPX case](#historical-benchmark) · [Run the examples](#run-the-examples) · [Use it on your PR](#run-on-demand)

## Which behavior should the test keep?

In each of these synthetic examples, all four existing tests pass on both commits. The stock-feed reviewer wants to preserve what buyers see. The booking-credit reviewer wants to keep a fix.

| Scenario and input | Base | Candidate | Exported test |
| --- | --- | --- | --- |
| Two units in stock, both reserved. What does the customer see? | `{"available": 0}` → **Sold out** | `{"available": false}` → **Check availability** | `--expect base` passes on base and fails on candidate. |
| A $10 booking credit, split across three groups of one ticket each. How much reaches the receipt? | $3.33 + $3.33 + $3.33 = **$9.99** | $3.34 + $3.33 + $3.33 = **$10.00** | `--expect head` fails on base and passes on candidate. |

**Stock feed.** A refactor guards the available count with `remaining > 0 and remaining`. At zero, Python returns `False`. The existing API test still passes because Python considers `False == 0`, and the listing test uses a hand-written numeric response. The probe feeds the actual API response to the listing, which expects an integer. Keeping the base result protects what a buyer sees when the last unit is reserved. [Patch](evaluation/replay-examples/stock-feed/candidate.patch) · [Existing tests](evaluation/replay-examples/stock-feed/source/existing_tests.py) · [Probe](evaluation/replay-examples/stock-feed/probe.py) · [Replay report](evaluation/replay-examples/results/stock-feed/report.json) · [Exported test](evaluation/replay-examples/results/stock-feed/regression.py)

**Booking credit.** The old allocator rounds each group's share down to a whole cent. The existing proportional-split test divides evenly. Undertow tries `[1, 1, 1]`, three $25 tickets grouped separately, and exposes the missing cent. The candidate distributes leftover cents by fractional share, with receipt order breaking ties. Keeping the candidate result makes the full $10 credit and its distribution part of the contract. [Patch](evaluation/replay-examples/credit-split/candidate.patch) · [Existing tests](evaluation/replay-examples/credit-split/source/existing_tests.py) · [Probe](evaluation/replay-examples/credit-split/probe.py) · [Replay report](evaluation/replay-examples/results/credit-split/report.json) · [Exported test](evaluation/replay-examples/results/credit-split/regression.py)

<a id="historical-benchmark-for-complementary-behavioral-coverage"></a>
<a id="historical-benchmark"></a>

## What changed in HTTPX?

HTTPX PR #3364 refactored request construction. Both commits passed all 1,425 tests in the local non-network run. But what happens when a caller passes `params={}` for a URL that already has a query? The base kept `?a=1`. The candidate cleared it. In a replay of the merged PR, Undertow exported a test for the new behavior. It failed on base and passed on the candidate. HTTPX later added an equivalent assertion. [Original PR](https://github.com/encode/httpx/pull/3364) · [Exported test](evaluation/results/product-value/test_httpx_request.py) · [Upstream test](https://github.com/encode/httpx/pull/3440)

Across 21 historical comparisons, automatic discovery found no eligible targets. Four reusable probes reproduced differences in five comparisons, including the HTTPX case above. [Benchmark results](evaluation/benchmark.md#what-the-probes-added) · [Click stdin trial](evaluation/click-replay.md)

## Run the examples

In the stock-feed probe, you set the stock level and pass the real inventory response to the listing. Undertow varies the reservation count. At two reservations, it reproduces the change from **Sold out** to **Check availability**. [Stock-feed probe](evaluation/replay-examples/stock-feed/probe.py)

```python
import json

from stock_feed import inventory_response, listing_text


def probe(reservations: int) -> dict:
    payload = inventory_response(on_hand=2, reserved=max(0, reservations))
    return {"feed": json.loads(payload), "listing": listing_text(payload)}
```

From the repository root, run all five examples with Git, Python 3.11+, pytest and IANA time-zone data. Use a fresh output directory. The runner creates both commits for each example and checks the original suite and the exported test on each. Then open the stock-feed summary to see the input and both outcomes.

```sh
python3 evaluation/replay-examples/run.py --output .undertow/replay-examples
cat .undertow/replay-examples/stock-feed/replay/summary.md
```

<a id="three-regressions-that-pass-the-existing-suite"></a>

The other three examples trace a missing refund, skipped invoices and a ferry that disappears from its departure board. [More runnable examples](evaluation/replay-examples/README.md) · [Test results for all five](evaluation/replay-examples/results/verification.json)

## Run on demand

After your PR’s CI passes, choose a public API scenario and write a probe for the outcome a caller can observe. Run it against the merge base and the candidate commit. If the results differ, inspect the input and both outcomes, then choose `--expect base` or `--expect head` to export your test. [Local commands and test export](docs/behavior-replay.md#probe-a-public-api-and-export-a-pytest-test)

The GitHub Actions example runs when a maintainer dispatches it with two commits and a reviewed probe. Results go to the run summary and artifact. The showcase replays the Click regression and exports the test in Actions. [Manual workflow](docs/examples/behavior-replay.yml) · [Runnable showcase](https://github.com/alpercandemir/undertow-showcase)

Undertow also has a manual pilot for changes to its own pytest exporter. Its probe checks whether a generated assertion can tell integer zero from boolean false. The pilot is designed to track ten live reviews and record whether replay changes a reviewer’s decision. [Pilot protocol and case log](evaluation/pytest-export-pilot.md) · [Manual pilot on GitHub Actions](https://github.com/alpercandemir/undertow/actions/workflows/pytest-export-pilot.yml)
