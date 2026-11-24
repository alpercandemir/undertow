<p align="center">
  <img src="docs/assets/undertow-logo.png" alt="Undertow logo" width="160">
</p>

<h1 align="center">Undertow</h1>

<p align="center">
  <a href="https://github.com/alpercandemir/undertow/releases/latest"><img src="https://img.shields.io/github/v/release/alpercandemir/undertow?label=release&amp;color=2563eb" alt="Latest release"></a>
  <a href="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml"><img src="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI status"></a>
</p>

Undertow posts GitHub pull request comments about behavior changes. Its source review reads the diff and shows inputs for which a rule changes. For a Python project, you can also select a pytest case and have the bot show what the same test observed before and after the change.

[Coupon rounding](#follow-a-pr-from-diff-to-result) · [Free shipping at $50](#same-cart-different-total) · [Returns and refunds](#try-a-product-review) · [Workflow setup](#get-a-report-on-your-next-pr)

## Follow a PR from diff to result

**Coupon rounding.** A shopper has three $3.35 items and a 10% coupon. The old checkout rounds each item's discount to $0.34, for a total discount of $1.02. The PR rounds once on the $10.05 subtotal, giving a $1.01 discount. A reviewer opening **Conversation → Undertow test replay** sees what each checkout calculated. [Read the bot’s comment](https://github.com/alpercandemir/undertow-showcase/pull/7#issuecomment-7134796175)

| Recorded by the same test | Before the PR | With the PR |
| --- | ---: | ---: |
| Three items · coupon discount | $1.02 | $1.01 |
| Three items · amount due | **$9.03** | **$9.04** |
| One item · amount due | $3.01 | $3.01 |

1. **The PR author supplies the scenario.** The three-item test calls checkout, records the returned amounts, then asserts that $9.04 is due. A second test covers a single item. The workflow selects both tests by name. [Tests](https://github.com/alpercandemir/undertow-showcase/blob/269992db47d4baa63769192495ccbf23103735ef/tests/test_coupon_checkout.py) · [Workflow](https://github.com/alpercandemir/undertow-showcase/blob/269992db47d4baa63769192495ccbf23103735ef/.github/workflows/coupon-replay.yml)
2. **GitHub Actions runs the comparison.** Undertow takes the test file from the PR and runs each selected case against the common ancestor and the PR head. The three-item assertion fails on the old calculation and passes on the new one. The single-item assertion passes on both. [Run summary and logs](https://github.com/alpercandemir/undertow-showcase/actions/runs/45417256463)
3. **The bot updates the PR conversation.** The replay comment shows each assertion result and the values the test recorded. A separate source-review comment reports zero findings on this diff. The rounding difference is visible because the checkout test exercised it. [Replay comment](https://github.com/alpercandemir/undertow-showcase/pull/7#issuecomment-7134796175) · [Source-review comment](https://github.com/alpercandemir/undertow-showcase/pull/7#issuecomment-7133483426)

## Same cart, different total

The shipping PR changes the free-delivery rule by one character.

```diff
-    if order_total > 50:
+    if order_total >= 50:
```

The bot picks the boundary input, `order_total = 50`, and shows that `qualifies_for_free_delivery` changes from `False` to `True`. Its comment also links to `shipping_fee` and `gift_checkout_total`, two places a reviewer can follow that decision. [Read the shipping bot comment](https://github.com/alpercandemir/undertow-showcase/pull/5#issuecomment-7037780019)

To check what the shopper would see, we ran the gift checkout function on both commits. At exactly $50, the $7 shipping fee disappears. The adjacent carts provide a control. [Reproduce the checkout totals](evaluation/runtime-verification.md#what-a-reviewer-would-see)

| Cart subtotal | Checkout total before | Checkout total after |
| --- | ---: | ---: |
| $49 | $56 | $56 |
| **$50** | **$57** | **$50** |
| $51 | $51 | $51 |

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

<a id="try-a-product-review"></a>

## Returns, refunds and a removed endpoint

The storefront PR shortens the return window from 30 days to 14. The bot highlights day 30, identifies the refund status change for a missing receipt, and flags the removed `POST /bulk-quotes` route. A reviewer can open each finding to see the old and new source lines. [Read the storefront bot comment](https://github.com/alpercandemir/undertow-showcase/pull/6#issuecomment-7039643058)

We then sent the same requests to both versions with Flask's test client. The first two rows show why the input matters. A receipt cannot rescue an expired return, while a missing receipt on day 7 now sends the request to a reviewer. [Runtime observations](evaluation/runtime-verification.md#observations)

| Request | Before | After |
| --- | --- | --- |
| `POST /refunds` · day 30, receipt attached | `PENDING` | `EXPIRED` |
| `POST /refunds` · day 7, no receipt | `REJECTED` | `MANUAL_REVIEW` |
| `POST /bulk-quotes` · `{"totals":[50]}` | HTTP 200 · `{"shipping":[7]}` | HTTP 404 |

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

Open or update a PR to get one source-review bot comment, an Actions summary, and Markdown and JSON reports. The built-in source review runs with Python and Git. [Example comment](https://github.com/alpercandemir/undertow-showcase/pull/5#issuecomment-7037780019)

`v0.4.0` adds a versioned JSON report with before-and-after examples, review questions, and source evidence. Optional Gemini analysis can add findings to the report alongside the local rules. [Release notes](https://github.com/alpercandemir/undertow/releases/tag/v0.4.0) · [Markdown example](docs/examples/summary.md) · [JSON example](docs/examples/report.json)

### Replay a PR test on both revisions

Add a read-only test job, install your project’s dependencies, and select a case such as `tests/test_coupon_checkout.py::test_coupon_rounds_once_on_cart_subtotal`. Use pytest’s `record_property` before the assertion to include an observed value, for example `record_property("undertow.Amount due", f"${quote['amount_due']}")`. Tests without these properties still report whether their assertions passed.

Add the publisher job from the showcase workflow to put the comparison in **Conversation** on same-repository PRs. Reviewers can follow the link in that comment to the run summary and logs. The example pins both replay actions to a commit that includes them. [Copy the working replay workflow](https://github.com/alpercandemir/undertow-showcase/blob/269992db47d4baa63769192495ccbf23103735ef/.github/workflows/coupon-replay.yml) · [Setup and local CLI](docs/test-replay.md)

## Supported changes

| Source change | What Undertow can show |
| --- | --- |
| An eligibility threshold moves from 30 to 14 days, including a supported named constant or multiline condition | The day-30 return changes from eligible to ineligible. The finding links to the source on both revisions. |
| An eligible refund without a receipt changes status | The finding shows the old and new return values. |
| An HTTP route is added or removed | The finding identifies the method and path, and distinguishes declarations on different routers. |
| Other code calls the edited rule | The trace follows callers for up to three steps and labels resolved calls and possible name matches. |

The Action reads Git source. Optional Gemini analysis sends selected source excerpts to Google to interpret related edits. Both modes produce source-based findings. See the guide for supported patterns, evidence labels, and coverage limits. [Analysis and configuration](docs/change-review.md) · [Controlled test cases](evaluation/README.md#controlled-fixtures-and-known-limitations)

<a id="historical-benchmark-for-complementary-behavioral-coverage"></a>

## Historical benchmark

We reviewed 20 historical Click and HTTPX PRs with Undertow and GitHub Copilot CLI. These are retrospective runs with saved reports in this repository. The upstream PRs provide the original code changes. [Full benchmark](evaluation/benchmark.md)

| PRs evaluated | Labeled changes found by Undertow + GitHub Copilot CLI | Additional labeled changes from Undertow |
| --- | ---: | ---: |
| 11 PRs both model-backed reviews completed | **10 / 11** | +1 |
| All 20 PRs | **16 / 18** | +1 |

The combined scores count each labeled change once across the two independent reports. Undertow's additional finding came from Click #2604. The command generated from `my_sync_command` changed from `my-sync-command` to `my-sync`, so scripts that use the old name may need updating. [Undertow report](evaluation/results/historical-gemini/click-2604/report.json)

### Benchmark scope and limits

The corpus contains ten Click PRs and ten HTTPX PRs with 18 labeled behavior changes. Nine PRs fixed bugs, seven changed behavior intentionally, and four preserved runtime behavior. One additional Click regression was scored separately. [Case manifest](evaluation/historical.json)

Undertow with Gemini completed 11 of 20 PRs. Seven fell back to local rules and two had no reviewable source regions. On the 11 shared PRs, **1 of 10** before/after examples from Undertow with Gemini and **12 of 18** from Copilot CLI were accurate. The combined scores measure detection coverage. [Run results](evaluation/benchmark.md#measured-results-on-the-frozen-20-prs) · [Example scoring](evaluation/benchmark.md#comparison-on-the-same-11-prs)

| Historical PR | What we observed on the two revisions | How the review compared |
| --- | --- | --- |
| [HTTPX #3367](https://github.com/encode/httpx/pull/3367) | Building a request with `{"key":"á"}` changed the encoded body from `{"key": "\u00e1"}` to `{"key":"á"}`. A number set to positive infinity produced an `Infinity` token before and raised `ValueError` afterward. | Both model-backed reviews flagged the three labeled serialization changes. |
| [HTTPX #3387](https://github.com/encode/httpx/pull/3387) | A repeated header yielded the values `one` and `two` on both revisions. A response’s `next` link resolved to the same URL. | Undertow with Gemini flagged a private helper move. The two public examples retained their behavior. |
| [Click #2934](https://github.com/pallets/click/pull/2934) | A command iterating over standard input printed `test` and exited with code 0 before. With the same input it printed the line, then `Aborted!` and exited with code 1. | Gemini identified the iterator exception change. Copilot connected it to the abort. This case was scored separately. |

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
