# Runtime verification trial

November 24, 2026. Running the same scenario against both revisions gave Undertow a way to show the customer outcome and catch mistakes in source-inferred examples. The evaluation covers two showcase PRs and six selected historical library PRs. The selection favored small, runnable scenarios and included a refactor control.

## What a reviewer would see

> **Observed on both revisions · A $50 gift cart**
>
> Shipping went from **$7 to $0**, bringing the gift checkout total from **$57 to $50**. The $49 and $51 carts returned the same totals on both revisions.
>
> Should a gift order qualify for free delivery at exactly $50?

Undertow supplied the $50 input from its local source report. The trial called the eligibility rule and both checkout functions at the pinned commits. [Shipping showcase PR](https://github.com/alpercandemir/undertow-showcase/pull/5)

## Observations

| Comparison | What the runs showed |
| --- | --- |
| [Showcase #6](https://github.com/alpercandemir/undertow-showcase/pull/6) | The Flask test client observed all ten reported changes. A day-30 return with a receipt moved from pending to expired. A day-7 refund without a receipt moved from rejected to manual review. The gift quote started returning HTTP 200 with a $50 total, and the bulk quote moved from HTTP 200 to 404. |
| [Click #2604](https://github.com/pallets/click/pull/2604) | The inferred command name matched both runs. The generated name changed from `my-sync-command` to `my-sync`. |
| [Click #2846](https://github.com/pallets/click/pull/2846) | The Zsh formatter escaped `a:b` when help text was present. With help text absent, the colon stayed unescaped. The saved example omitted the leading `plain` line of the formatted output. |
| [Click #2972](https://github.com/pallets/click/pull/2972) | Both missing and invalid option errors dropped the stray environment-variable hint. The invalid-option message included quotes around the option name on both revisions. |
| [HTTPX #2998](https://github.com/encode/httpx/pull/2998) | The saved example claimed the line decoder changed from a list with an empty string to an empty list. It returned an empty list on both revisions. A separate public-API probe showed that `Response.iter_text()` dropped the final empty string. |
| [HTTPX #3367](https://github.com/encode/httpx/pull/3367) | The saved example identified the UTF-8 change. The observed head body was `{"key":"á"}`. The infinity probe raised `ValueError` on head after producing an `Infinity` JSON token on base. |
| [HTTPX #3387](https://github.com/encode/httpx/pull/3387) | The two public header observations agreed across the refactor. The saved review had reported a private-helper move. |

The shipping and storefront PRs and six library PRs used small, hand-written probes. They ran twice per revision in fresh Python processes and produced consistent observations. The shipping and storefront probes took 0.85–1.44 seconds per PR including the source report and both revisions. The six library replays took 0.35–0.62 seconds per PR after dependency setup.

The pilot used four numeric inputs from the storefront report and the $50 input from the shipping report. The probes encode the endpoints, the missing scenario details and all library inputs. The streaming probe came from the upstream regression test. These runs measure what a paired execution can establish once it has a runnable case. They leave open how often Undertow can find or translate such a case, and whether reviewers would act on the report. No maintainers reviewed these results in the pilot.

For a generated input that extends an existing passing suite, see the [Click stdin regression](click-replay.md).

## Reproduce

Prepare the Click and HTTPX bare clones using the [benchmark instructions](competitive-pilot.md#prepare-the-repositories). Run this local-only experiment on the pinned, trusted repositories. Each replay imports code from the snapshot into a local process. The process uses a reduced environment and a timeout. The released [PR reporting workflow](../docs/change-review.md) still uses source inspection.

```sh
trial=.undertow/runtime-verification
mkdir -p "$trial/repos"
git clone --bare https://github.com/alpercandemir/undertow-showcase.git "$trial/repos/showcase.git"
git -C "$trial/repos/showcase.git" fetch origin \
  refs/pull/5/head:refs/trial/5 refs/pull/6/head:refs/trial/6

uv venv --python 3.12 "$trial/venv"
uv pip install --python "$trial/venv/bin/python" \
  'Flask==3.1.2' 'httpcore==1.0.9' 'anyio==4.9.0' 'idna==3.10' \
  'certifi==2025.4.26' 'sniffio==1.3.1'

"$trial/venv/bin/python" evaluation/runtime_trial.py \
  --repos .undertow/eval-repos --showcase-repo "$trial/repos/showcase.git" \
  --output "$trial/replay"
```

`observations.json` records the exact commits, saved finding fingerprints and both outcomes. The two fresh source reports sit alongside it. A failed or inconsistent replay makes the runner exit nonzero.
