# Replay generated Python inputs

Behavior replay compares the same generated call on the PR's merge base and exact head. For example, Undertow found a basket of two $1.05 items where coupon rounding changes the amount due from $1.88 to $1.89. The report includes the reproducing call and both observed outcomes. A reviewer can decide whether the change matches the intended policy and use the call in a regression test. [Coupon showcase](https://github.com/alpercandemir/undertow-showcase/pull/7)

## Run locally

Use a current Python 3.11+ on Linux or macOS, Git, and the project dependencies needed to import the changed module. Use trusted revisions. The CLI exports both commits to temporary directories and executes the selected modules.

```sh
python3 -IB actions/behavior-replay/discover.py \
  --repo /path/to/project \
  --base "$BASE_SHA" --head "$HEAD_SHA" \
  --output .undertow/behavior
```

Supply full commit IDs and choose a fresh output directory. The CLI writes `report.json` and `summary.md`. It exits zero when comparisons finish, one when a comparison is unavailable, and two for invalid arguments.

## Run in GitHub Actions

Use the two-job showcase workflow as a starting point. To use the release, select `alpercandemir/undertow/actions/behavior-replay@v0.5.0` in the replay job and `alpercandemir/undertow/actions/replay-comment@v0.5.0` in the publish job. Keep runtime execution in the `pull_request` job with `contents: read`, an ephemeral runner, and no secrets or persistent checkout credentials. If the project needs dependencies, install them in that job. Use a separate publish job with write access only for same-repository PRs. [Copy the showcase workflow](https://github.com/alpercandemir/undertow-showcase/blob/codex/coupon-rounding-baseline/.github/workflows/coupon-replay.yml)

The publisher updates one replay comment with the first reproduced difference per callable. If nothing is publishable for the current comparison, the existing replay comment is removed and the Actions summary remains available.

To suppress an observation already shown in the PR description, add `<!-- undertow-covered: EVIDENCE_ID -->` to the description. The Actions summary provides the ID. A CI job that already displays the same evidence can pass its IDs to the publisher's `covered-evidence` input as a space-separated list. Each ID includes the callable, input, and both outcomes. A different input or result has a different ID.

## Coverage

The built-in search covers changed top-level Python functions with one positional argument annotated `int`, `float`, `Decimal`, or a list of one of these types. It looks at modified modules in a flat or conventional `src/` layout. It tries numeric boundaries from the function and small numeric samples, including repeated list values. It examines at most twelve modified modules and six eligible functions per run.

For a finding, the generated input must produce stable outcomes in fresh processes and the difference must reproduce with that input alone. Supported results include small scalars, arrays, and dictionaries. A return becoming an exception is reported as a new exception for that input. Import failures, timeouts, and cases without comparable results are explained in the Actions summary. Finishing without a finding means no difference was observed for the inputs attempted.

Use source review for a wider view of edited rules and callers, or [pytest replay](test-replay.md) to check an existing assertion.
