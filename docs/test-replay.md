# Replay a focused pytest test

Use Undertow test replay when you need to see how the old revision handles a specific expectation added or changed by the PR. Select one exact pytest node ID from head, including the parameter ID when applicable. For Undertow-generated inputs, use [behavior replay](behavior-replay.md).

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

### Include observed values in an opted-in PR comment

The replay job writes the assertion result to its Actions summary. To include a calculated value in the PR comment, record it before the assertion so it is available even when that assertion fails.

```python
def test_coupon_rounds_once_on_cart_subtotal(record_property):
    quote = quote_coupon_cart([Decimal("3.35")] * 3)
    record_property("undertow.Amount due", f"${quote['amount_due']}")
    assert quote["amount_due"] == Decimal("9.04")
```

Pytest supplies the `record_property` fixture. Undertow includes up to 12 properties per revision with the `undertow.` prefix. Labels after the prefix can be 1–55 characters. Use strings, numbers or booleans whose text is at most 160 characters. Choose values suitable for the PR audience.

The Actions summary is the default destination for pytest replay. For a PR where an assertion transition adds context, run the publisher from a commit that supports `publish-test-replay` and set it to `'true'`. The example assumes the producer job is named `replay`, uses artifact names beginning with `coupon-`, and exposes the producer's attempt as a job output. Only base-to-head assertion transitions are eligible for a comment. Equal test outcomes and unavailable comparisons stay in Actions.

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
      - uses: alpercandemir/undertow/actions/replay-comment@d9b6220bf2238887f7d438dbc8fee1d7b17412b5
        with:
          artifact-pattern: coupon-*-replay-*-${{ needs.replay.outputs.attempt }}
          publish-test-replay: 'true'
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

In an earlier trial, the three-item coupon test failed on base at $9.03 and passed on head at $9.04. Its single-item control passed on both commits. Replay uses the PR test's expectation in both runs, so this demonstrates when that expectation first holds. [Pinned historical reproduction](../evaluation/runtime-verification.md#reproduce-the-original-pytest-replay)
