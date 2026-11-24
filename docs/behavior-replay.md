# Replay generated Python inputs

Behavior replay compares the same generated input on the PR's merge base and exact head. Use it after CI passes to look for behavior beyond the cases in the existing suite. The report records the reproducing input and both observed results. A reviewer decides which behavior the contract calls for. [Click stdin regression](../evaluation/click-replay.md)

## Run locally

Use a current Python 3.11+ on Linux or macOS, Git, and the project dependencies needed to import the changed module. Use trusted revisions. The CLI exports both commits to temporary directories and executes the selected modules.

```sh
python3 -IB actions/behavior-replay/discover.py \
  --repo /path/to/project \
  --base "$BASE_SHA" --head "$HEAD_SHA" \
  --output .undertow/behavior
```

Supply full commit IDs and choose a fresh output directory. The CLI writes `report.json` and `summary.md`. It exits zero when comparisons finish, one when a comparison is unavailable, and two for invalid arguments.

## Probe a public API and export a pytest test

Use `--probe` from this checkout when the behavior spans several functions or requires a public entry point such as a CLI. The trusted probe defines one `probe(value)` function with a supported type annotation. It sets up the call and returns a small JSON value that captures the observable behavior. Use one self-contained file and import project code through the public API. Undertow snapshots that file and supplies the inputs on both revisions. [Click probe](../evaluation/probes/click_stdin.py)

```sh
python3 -IB actions/behavior-replay/discover.py \
  --repo /path/to/project --base "$BASE_SHA" --head "$HEAD_SHA" \
  --probe path/to/probe.py --output .undertow/probe-run

python3 -IB actions/behavior-replay/write_test.py \
  --report .undertow/probe-run/report.json --probe path/to/probe.py \
  --expect base --output .undertow/test_behavior_regression.py
```

Choose `--expect base` when the existing behavior is the reviewed contract or `--expect head` when the new behavior is intentional. The exporter binds the test to the probe digest in the finding. It copies the probe and adds a pytest assertion for the chosen outcome. Use `--finding EVIDENCE_ID` when selecting from multiple findings. Run the resulting test against both revisions before adding it to your suite. [Generated example](../evaluation/results/click-probe/test_undertow_stdin.py)

## Publish review evidence after CI

Add replay after the existing test job in your `pull_request` workflow. The [example](examples/behavior-replay.yml) uses the released direct-function replay and retains findings in Actions. A maintainer can request publication for the current PR by applying the `undertow-publish` label. The publish job requires a passing test job and a successful replay in that workflow run. Keep runtime execution in an ephemeral read-only job with project dependencies, no secrets, and `persist-credentials: false`. The separate publish job has write access for same-repository PRs and reads only the replay artifact. [GitHub job dependency results](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#needs-context)

Review the evidence before requesting a comment. A green check establishes that the existing suite passed. Reviewers establish whether the finding adds information about the contract. If CI or the PR already shows the evidence, add `<!-- undertow-covered: EVIDENCE_ID -->` to the description or pass its ID via the publisher's `covered-evidence` input. An ID identifies the exact observation, including the input and both outcomes.

## Coverage

The built-in search covers changed top-level Python functions with one positional argument annotated `int`, `float`, `Decimal`, or a list of one of these types. It looks at modified modules in a flat or conventional `src/` layout. A supplied probe uses the same input generator with its annotated argument and drives the public API chosen by the probe author. The search tries numeric boundaries from the tested function and small numeric samples, including repeated list values. Automatic discovery examines at most twelve modified modules and six eligible functions per run.

For a finding, the generated input must produce stable outcomes in fresh processes and the difference must reproduce with that input alone. Supported results include small scalars, arrays, and dictionaries. A return becoming an exception is reported as a new exception for that input. Import failures, timeouts, and cases without comparable results are explained in the Actions summary. Finishing without a finding means no difference was observed for the inputs attempted.

Use [source review](change-review.md) for a wider source-only view of edited rules and callers.
