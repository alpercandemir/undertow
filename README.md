<p align="center">
  <img src="docs/assets/undertow-logo.png" alt="Undertow logo" width="160">
</p>

<h1 align="center">Undertow</h1>
<p align="center"><strong>See what a pull request changes for customers</strong></p>

<p align="center">
  <a href="https://github.com/alpercandemir/undertow/releases/latest"><img src="https://img.shields.io/github/v/release/alpercandemir/undertow?label=release&amp;color=2563eb" alt="Latest release"></a>
  <a href="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml"><img src="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI status"></a>
</p>

Undertow brings concrete customer and API scenarios into a pull request review. The **source review** identifies a specific input whose outcome may change and links to the code on both commits. **Test replay** runs a pytest case you select against both commits so you can see what actually happens.

[See the $50 cart](#same-cart-different-total) · [Replay a PR test](#replay-a-pr-test-on-both-revisions) · [Storefront examples](#try-a-product-review) · [Install the Action](#get-a-report-on-your-next-pr) · [Historical benchmark](#historical-benchmark-for-complementary-behavioral-coverage)

## Same cart, different total

A one-character change makes a **$50 gift cart go from $57 to $50**.

```diff
-    if order_total > 50:
+    if order_total >= 50:
```

Undertow picks **exactly $50** from the diff. Its source report links the eligibility rule to the shipping fee and gift checkout calculation. The table shows the totals we observed when we ran those functions on both commits. [PR comment](https://github.com/alpercandemir/undertow-showcase/pull/5#issuecomment-7037780019) · [Execution results](evaluation/runtime-verification.md#what-a-reviewer-would-see)

| Cart subtotal | Checkout total before | Checkout total after |
| --- | ---: | ---: |
| $49 | $56 | $56 |
| **$50** | **$57** | **$50** |
| $51 | $51 | $51 |

> Should a gift order qualify for free delivery the moment it reaches $50?

<details>
<summary>Reproduce the source finding locally</summary>

From a checkout of Undertow `v0.4.0`, run these commands with Python 3.11+ and Git. The worker reads the showcase's committed source from a bare repository. Local mode uses no model key.

```sh
mkdir -p .undertow
git clone --bare https://github.com/alpercandemir/undertow-showcase.git .undertow/showcase.git
git -C .undertow/showcase.git fetch origin refs/pull/5/head
env -u GEMINI_API_KEY python3 actions/change-review/review_engine.py \
  --repo .undertow/showcase.git \
  --base 7f831b0d911228ba608bfb9f9ab553933190348e \
  --head 1aee946a9ad10474b6971acc909fd29b6d70f261 \
  --source-url https://github.com/alpercandemir/undertow-showcase \
  --output .undertow/shipping-review
cat .undertow/shipping-review/summary.md
```

Open `report.json` in the same output directory for the input, both inferred outcomes, and the source lines behind the comparison. The report also links the calls from `shipping_fee` and `gift_checkout_total`.

</details>

## Replay a PR test on both revisions

**Why does the same basket cost one cent more?** Apply a 10% coupon to three $3.35 items. The old checkout rounds the discount on each item to $0.34 and adds them up. The PR rounds 10% of the $10.05 subtotal once, to $1.01.

| Checkout result | Base · round per item | PR · round on subtotal |
| --- | ---: | ---: |
| Coupon discount | $1.02 | $1.01 |
| Amount due | **$9.03** | **$9.04** |
| Same test, expecting $9.04 due | Failed | Passed |

The PR adds the test. Undertow takes that same test file and runs it against the old and new checkout code. A single $3.35 item still costs $3.01 on both commits and its test passes on both. The local source review produced zero findings for the rounding change. [Replay report on Showcase PR #7](https://github.com/alpercandemir/undertow-showcase/pull/7#issuecomment-7133488076)

Choose one pytest case to try opt-in replay from this checkout. You can run it locally or in a separate GitHub Actions job. [Run a replay](docs/test-replay.md) · [Reproduce the coupon example](evaluation/runtime-verification.md#replay-the-coupon-showcase)

## Try a product review

A storefront release cuts the return window from 30 days to 14, changes how refunds without a receipt are handled, and removes an API route. The PR comment points to the rules and route declarations. We also sent the same requests to both versions with Flask's test client. [Storefront PR comment](https://github.com/alpercandemir/undertow-showcase/pull/6#issuecomment-7039643058) · [Execution results](evaluation/runtime-verification.md#observations)

| Same request | Before | After | What to decide |
| --- | --- | --- | --- |
| **Return on day 30 with a receipt** | Pending | Expired | Which return window applies to orders already placed? |
| **Refund on day 7 without a receipt** | Rejected | Manual review | Who handles the new requests in the queue? |
| **Request a bulk quote** | HTTP 200 | HTTP 404 | Which integrations still use this endpoint? |

<details>
<summary>See all ten storefront findings</summary>

<p align="center">
  <a href="https://github.com/alpercandemir/undertow-showcase/pull/6#issuecomment-7039643058">
    <img src="docs/assets/storefront-impact.svg" width="820" alt="Storefront release review with ten findings across returns, payments, shipping, refunds, fulfillment, rewards, inventory, and quote APIs">
  </a>
</p>

</details>

## Get a report on your next PR

Add the example workflow at `.github/workflows/undertow.yml` on your base branch. It includes the PR trigger, permissions, and this step pinned to the published release. [Copy the workflow](docs/examples/change-review.yml)

```yaml
- uses: alpercandemir/undertow/actions/change-review@v0.4.0
  with:
    comment: 'true'
```

Open or update a PR to get one bot comment, an Actions summary, and Markdown and JSON reports. The built-in source review runs with Python and Git. [Example comment](https://github.com/alpercandemir/undertow-showcase/pull/5#issuecomment-7037780019)

`v0.4.0` adds a versioned JSON report with before-and-after examples, review questions, and source evidence. Optional Gemini analysis can add findings to the report alongside the local rules. [Release notes](https://github.com/alpercandemir/undertow/releases/tag/v0.4.0) · [Markdown example](docs/examples/summary.md) · [JSON example](docs/examples/report.json)

## Supported changes

| Source change | What Undertow can show |
| --- | --- |
| An eligibility threshold moves from 30 to 14 days, including a supported named constant or multiline condition | The day-30 return changes from eligible to ineligible. The finding links to the source on both revisions. |
| An eligible refund without a receipt changes status | The finding shows the old and new return values. |
| An HTTP route is added or removed | The finding identifies the method and path, and distinguishes declarations on different routers. |
| Other code calls the edited rule | The trace follows callers for up to three steps and labels resolved calls and possible name matches. |

The Action reads Git source. Optional Gemini analysis sends selected source excerpts to Google to interpret related edits. Both modes produce source-based findings. See the guide for supported patterns, evidence labels, and coverage limits. [Analysis and configuration](docs/change-review.md) · [Controlled test cases](evaluation/README.md#controlled-fixtures-and-known-limitations)

## Historical benchmark for complementary behavioral coverage

We ran Undertow with Gemini and GitHub Copilot CLI independently on 20 historical Click and HTTPX PRs to see which behavior changes each review surfaced. The Copilot results come from the CLI with a fixed review prompt. [Full benchmark](evaluation/benchmark.md)

| PRs evaluated | Labeled changes found by Undertow + GitHub Copilot CLI | Additional labeled changes from Undertow |
| --- | ---: | ---: |
| 11 PRs both model-backed reviews completed | **10 / 11** | +1 |
| All 20 PRs | **16 / 18** | +1 |

The combined scores count each labeled change once across the two independent reports. Undertow's additional finding came from Click #2604. The command generated from `my_sync_command` changed from `my-sync-command` to `my-sync`, so scripts that use the old name may need updating. [Undertow report](evaluation/results/historical-gemini/click-2604/report.json)

### Benchmark scope and limits

The corpus contains ten Click PRs and ten HTTPX PRs with 18 labeled behavior changes. Nine PRs fixed bugs, seven changed behavior intentionally, and four preserved runtime behavior. One additional Click regression was scored separately. [Case manifest](evaluation/historical.json)

Undertow with Gemini completed 11 of 20 PRs. Seven fell back to local rules and two had no reviewable source regions. On the 11 shared PRs, **1 of 10** before/after examples from Undertow with Gemini and **12 of 18** from Copilot CLI were accurate. The combined scores measure detection coverage. [Run results](evaluation/benchmark.md#measured-results-on-the-frozen-20-prs) · [Example scoring](evaluation/benchmark.md#comparison-on-the-same-11-prs)

| From the benchmark | Before → after | Result |
| --- | --- | --- |
| [HTTPX #3367](https://github.com/encode/httpx/pull/3367) · JSON validation | A request body with NaN was serialized → serialization raises an error. | Both model-backed reviews identified all three labeled JSON changes in this PR. |
| [HTTPX #3387](https://github.com/encode/httpx/pull/3387) · Refactor control | Helpers move while header and response handling stays the same. | Gemini reported a private-helper move as a finding. Local rules and Copilot issued no findings. |
| [Click #2934](https://github.com/pallets/click/pull/2934) · Supplemental regression | A command reading a line from standard input exits normally → aborts at end of input. | Gemini identified the iterator exception change. Copilot connected it to the command abort. Scored separately. |

[Browse all 20 benchmark PRs](evaluation/benchmark.md#per-pr-results)

<details>
<summary>Recompute the benchmark scores</summary>

Run from the repository root with Python 3.11+. The checked-in runs can be scored without provider credentials.

```sh
python3 evaluation/run.py score evaluation/results/historical-local.json
python3 evaluation/run.py score evaluation/results/historical-copilot.json \
  --adjudications evaluation/results/historical-copilot-adjudications.json
python3 evaluation/run.py score evaluation/results/historical-gemini/run.json \
  --adjudications evaluation/results/historical-gemini/adjudications.json
```

The detailed report includes example accuracy, precision, false positives, timing, provider costs, and evaluation limits. [Detailed results](evaluation/benchmark.md) · [Rerun the benchmark](evaluation/competitive-pilot.md)

</details>
