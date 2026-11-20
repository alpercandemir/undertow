<p align="center">
  <img src="docs/assets/undertow-logo.png" alt="Undertow logo" width="160">
</p>

<h1 align="center">Undertow</h1>
<p align="center"><strong>Business change reports for GitHub pull requests</strong></p>

<p align="center">
  <a href="https://github.com/alpercandemir/undertow/releases/latest"><img src="https://img.shields.io/github/v/release/alpercandemir/undertow?label=release&amp;color=2563eb" alt="Latest release"></a>
  <a href="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml"><img src="https://github.com/alpercandemir/undertow/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI status"></a>
</p>

A store launches gift quotes. In the same release it retires bulk quotes, shortens the returns window, and changes who gets free shipping. Which existing clients and customers feel those edits first?

Undertow posts a prioritized source review on the pull request. Open a finding to see the concrete input, before-and-after outcomes, the reason for its priority, and links into both Git revisions. [Read the live bot report](https://github.com/alpercandemir/undertow-showcase/pull/6#issuecomment-7039643058) · [Inspect the release diff](https://github.com/alpercandemir/undertow-showcase/pull/6/files)

## One storefront release, ten business changes

![Storefront release impact with three compatibility risks, six changed outcomes and one new endpoint](docs/assets/storefront-impact.svg)

The table follows the store's policies through their callers. For example, the engine identifies a rewards multiplier changing from 1 to 3. The 150-point total comes from following the order quote's multiplication for a $50 cart. [Storefront source](https://github.com/alpercandemir/undertow-showcase/blob/626622ca0752042e698487cf9cae7d67ed90c1f1/src/storefront.py)

| Review | Try this input | Before → after | Where to look |
| --- | --- | --- | --- |
| P1 | A client posts a list of order totals to `/bulk-quotes` | Route declared → declaration removed | Bulk pricing clients |
| P1 | Return an item 30 days after delivery | Eligible → window expired | Refund intake |
| P1 | Request a 12-month payment plan | Eligible → ineligible | Installment offers |
| P2 | Quote a $50 cart | $7 shipping → free shipping | Standard and gift checkout |
| P2 | Submit an eligible refund without a receipt | Rejected → manual review | Refund state |
| P2 | Request an express dispatch estimate | 2 days → 1 day | Order quote |
| P2 | Earn points on a $50 order | 50 → 150 points | Quote uses points per dollar |
| P2 | Retry after three delivery attempts | Ineligible → eligible | Delivery retry decision |
| P2 | Ask whether an item can be backordered | Disabled → enabled | Backorder field in the order quote |
| P3 | Post a $50 order to `/gift-quotes` | New route on the head revision, returning a $50 gift total | Existing gift checkout and shipping policy |

### Why a reviewer should open the gift quote finding

The new endpoint reuses `gift_checkout_total`. That function adds the shipping fee to the subtotal, and the fee depends on the edited free-delivery rule. At exactly $50, the existing gift calculation changes from $57 to $50. The newly added endpoint exposes that same calculation. An order for $51 would hide the boundary change.

```mermaid
flowchart LR
    API["New: POST /gift-quotes"] --> Gift["Gift checkout total"]
    Gift --> Fee["Shipping fee"]
    Bulk["Existing: standard order quote"] --> Fee
    Fee --> Rule["Changed: free delivery at $50"]
    classDef added fill:#14403f,color:#fff,stroke:#63cfb9
    classDef changed fill:#513d17,color:#fff,stroke:#edc578
    class API added
    class Rule changed
```

Undertow follows possible callers up to three steps beyond an edited method. An arrow in the report links to a name match at a particular revision. The reviewer can follow those links to see how a caller uses the result. [Explore the smaller one-line shipping PR](https://github.com/alpercandemir/undertow-showcase/pull/5#issuecomment-7037780019)

<details>
<summary>See the underlying patterns in Java, Go and TypeScript</summary>


These small Git comparisons were run with the built-in analysis in v0.2.1. The examples below summarize what it found.

### A refund arrives without a receipt · Java

```diff
     if (receiptMissing) {
-        return "REJECTED";
+        return "MANUAL_REVIEW";
     }
```

The same refund now leaves the policy with the state `MANUAL_REVIEW`. The return statement is enough to establish that change. To find out whether someone actually receives a review task, follow the caller that consumes the state.

### A delivery has already been attempted three times · Go

```diff
-    return attempts < 3
+    return attempts < 5
```

At `attempts = 3`, the old retry check returns `false` and the new one returns `true`. That gives the reviewer a concrete case where another attempt becomes eligible. A first-attempt example would give the same answer on both revisions.

### A returns endpoint appears · TypeScript

```diff
+router.post("/returns", createReturn);
```

For a client sending `POST /returns`, the head revision now declares a route to `createReturn`. The report links to the new declaration. The handler determines which requests succeed and what the client receives.

A fourth comparison added only a comment above an unchanged Python delivery fee. Undertow produced an empty findings list. Each report includes its reviewed scope so a reader can see what was examined.

</details>

## Get a report on your next PR

Copy the [ready-to-use workflow](docs/examples/change-review.yml) into `.github/workflows/undertow.yml` on your base branch. It includes the PR trigger, token permissions, and this Action step.

```yaml
- uses: alpercandemir/undertow/actions/change-review@v0.3.0
  with:
    comment: 'true'
```

Open a PR or push another commit to it. The job writes an Actions summary and saves `summary.md` and `report.json`. With comments enabled, `github-actions[bot]` updates one comment using the workflow token. The PR description can be empty. Findings are advisory. [Open the showcase CI run](https://github.com/alpercandemir/undertow-showcase/actions/runs/44724616897)

## What goes into a finding?

**Built-in analysis** runs on the GitHub runner. It recognizes common numeric conditions, changed literal returns, and HTTP route declarations. For recognized methods it searches both source trees and follows possible callers up to three steps. A same-name match is a lead for the reviewer, especially in code that uses dynamic dispatch.

**Optional Gemini analysis** interprets changes that span languages or files. When you pass `GEMINI_API_KEY` to the Action, it sends Google selected source excerpts and caller context. Gemini can request more surrounding code or look up identifiers already present in the review. Each accepted finding cites a changed region. The examples describe outcomes inferred from source. [Configuration, data sharing, and analysis limits](docs/change-review.md)
