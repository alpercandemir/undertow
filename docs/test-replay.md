# Replay a focused pytest test

Use Undertow test replay to see whether the same test passes on both Git revisions. Start with a focused test that a PR adds for a customer-facing outcome such as a discount, eligibility rule, or API response. Select one exact pytest node ID from head, including the parameter ID when applicable.

## Run locally

Use a current Python 3.11+ on Linux or macOS. Install pytest 8 and the project's test dependencies in the Python environment used to invoke replay. Both revisions use that environment. The runner adds the snapshot root and its conventional `src/` directory to the Python import path. Use trusted revisions because pytest runs the selected test, its fixtures and project code.

```sh
python3 actions/test-replay/replay.py \
  --repo /path/to/project \
  --base "$BASE_SHA" --head "$HEAD_SHA" \
  --test 'tests/test_checkout.py::test_discount_rounding' \
  --output .undertow/checkout-replay
```

Supply full commit IDs. To align with Undertow source review on a PR, use the PR's merge base and exact head. Use a fresh output directory for each run. Pass shared runner options with `--pytest-args '["--sqlite", "-q"]'` or set the per-revision time limit with `--timeout`.

The runner exports both Git trees and overlays the selected test file from head onto base. Each revision runs the same file with its own committed application code, pytest configuration and other fixtures. If the test needs a new helper in another file or the revisions require incompatible dependencies, the replay can be unavailable.

## Run in GitHub Actions

Add a separate, ephemeral job in a `pull_request` or maintainer-dispatched workflow. Give it `contents: read`, check out both commits with `persist-credentials: false`, and install the project's test dependencies before running replay. Keep the job free of secrets and outside `pull_request_target`. The snippets below assume an earlier `revisions` step exposes `base` and `head` commit IDs. Pin Undertow to a commit containing the replay Action. During development, use `./actions/test-replay` from a checkout of this repository.

```yaml
- uses: alpercandemir/undertow/actions/test-replay@8875d27f44dc736061b5097b297e948774d4d8bd
  with:
    base: ${{ steps.revisions.outputs.base }}
    head: ${{ steps.revisions.outputs.head }}
    test: tests/test_checkout.py::test_discount_rounding
```

The Action puts the comparison in the job summary, uploads the reports and pytest logs, and exposes a `status` output. The result is advisory. The local CLI saves the same files in its output directory. Use fixtures suitable for sharing in assertions and logs. [GitHub's workflow security guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)

### Post the comparison to the PR

The replay job writes the assertion result to its Actions summary. To include a calculated value in the PR comment, record it before the assertion so it is available even when that assertion fails.

```python
def test_coupon_rounds_once_on_cart_subtotal(record_property):
    quote = quote_coupon_cart([Decimal("3.35")] * 3)
    record_property("undertow.Amount due", f"${quote['amount_due']}")
    assert quote["amount_due"] == Decimal("9.04")
```

Pytest supplies the `record_property` fixture. Undertow includes up to 12 properties per revision with the `undertow.` prefix. Labels after the prefix can be 1–55 characters. Use strings, numbers or booleans whose text is at most 160 characters. Choose values suitable for the PR audience.

Run the publisher in a separate job after replay. The example below assumes the producer job is named `replay`, uses artifact names beginning with `coupon-`, and exposes the attempt that uploaded them as a job output. This keeps retries tied to the producer's artifacts. The publisher reads JSON and updates one bot comment on the current same-repository PR. The read-only replay job also works without the publisher. [Complete workflow including both jobs](https://github.com/alpercandemir/undertow-showcase/blob/269992db47d4baa63769192495ccbf23103735ef/.github/workflows/coupon-replay.yml)

```yaml
  publish:
    needs: replay
    if: github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-24.04
    permissions:
      contents: read
      actions: read
      pull-requests: write
    steps:
      - uses: alpercandemir/undertow/actions/replay-comment@8875d27f44dc736061b5097b297e948774d4d8bd
        with:
          artifact-pattern: coupon-*-replay-*-${{ needs.replay.outputs.attempt }}
```

## Read the result

| Status | What happened |
| --- | --- |
| `passes_on_head_only` | Base failed an assertion in the test body and head passed. |
| `passes_on_base_only` | Base passed and head failed an assertion in the test body. |
| `passes_on_both` | Both revisions met the selected test's assertions. |
| `assertion_failed_on_both` | Both revisions failed an assertion in the selected test body. |
| `unavailable` | At least one revision did not produce a comparable result. Examples include missing dependencies, multiple collected cases, skips, expected failures and fixture errors. |

The CLI exits zero for a completed comparison and one when replay is unavailable. Invalid arguments exit two. The JSON records each commit, both statuses, and the SHA-256 of the shared test file. A replay covers only the selected assertion. A difference on a single run can warrant a rerun, especially for a test that depends on time, randomness, or external services.

In the coupon showcase, a three-item checkout test failed on base at $9.03 and passed on head at $9.04. The same single-item test passed on both commits. Undertow's local source review reported zero findings for the rounding change. [Replay report on Showcase PR #7](https://github.com/alpercandemir/undertow-showcase/pull/7#issuecomment-7134796175) · [Reproduce the coupon replay](../evaluation/runtime-verification.md#replay-the-coupon-showcase)
