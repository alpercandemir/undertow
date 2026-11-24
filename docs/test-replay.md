# Replay a focused pytest test

Test replay is a diagnostic for investigating one known assertion across revisions. Select one exact pytest node ID from head, including the parameter ID when applicable. Run your ordinary test suite in CI and use [behavior replay](behavior-replay.md) to look for additional inputs after it passes.

## Run locally

Use a current Python 3.11+ on Linux or macOS. Install pytest 8 and the project's test dependencies in the Python environment used to invoke replay. Both revisions use that environment. The runner adds the snapshot root and its conventional `src/` directory to the Python import path. Use trusted revisions because pytest runs the selected test, its fixtures and project code.

```sh
python3 actions/test-replay/replay.py \
  --repo /path/to/project \
  --base "$BASE_SHA" --head "$HEAD_SHA" \
  --test 'tests/test_parser.py::test_empty_input' \
  --output .undertow/checkout-replay
```

Supply full commit IDs. To align with Undertow source review on a PR, use the PR's merge base and exact head. Use a fresh output directory for each run. Pass shared runner options with `--pytest-args '["--sqlite", "-q"]'` or set the per-revision time limit with `--timeout`.

The runner exports both Git trees and overlays the selected test file from head onto base. Each revision runs the same file with its own committed application code, pytest configuration and other fixtures. If the test needs a new helper in another file or the revisions require incompatible dependencies, the replay can be unavailable.

## Run in GitHub Actions

When validating a known fix across revisions, use a separate, ephemeral job in a maintainer-dispatched workflow. Give it `contents: read`, check out both commits with `persist-credentials: false`, and install the project's test dependencies before running replay. Keep the job free of secrets. The snippet assumes an earlier `revisions` step exposes `base` and `head` commit IDs. During development, use `./actions/test-replay` from a checkout of this repository.

```yaml
- uses: alpercandemir/undertow/actions/test-replay@v0.5.0
  with:
    base: ${{ steps.revisions.outputs.base }}
    head: ${{ steps.revisions.outputs.head }}
    test: tests/test_parser.py::test_empty_input
```

The Action puts the comparison in the job summary, uploads the reports and pytest logs, and exposes a `status` output. The result is advisory. The local CLI saves the same files in its output directory. Use fixtures suitable for sharing in assertions and logs. [GitHub's workflow security guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)

## Read the result

| Status | What happened |
| --- | --- |
| `passes_on_head_only` | Base failed an assertion in the test body and head passed. |
| `passes_on_base_only` | Base passed and head failed an assertion in the test body. |
| `passes_on_both` | Both revisions met the selected test's assertions. |
| `assertion_failed_on_both` | Both revisions failed an assertion in the selected test body. |
| `unavailable` | At least one revision did not produce a comparable result. Examples include missing dependencies, multiple collected cases, skips, expected failures and fixture errors. |

The CLI exits zero for a completed comparison and one when replay is unavailable. Invalid arguments exit two. The JSON records each commit, both statuses, and the SHA-256 of the shared test file. A replay covers only the selected assertion. A difference on a single run can warrant a rerun, especially for a test that depends on time, randomness, or external services.
