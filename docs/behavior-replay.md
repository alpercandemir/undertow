# Replay generated Python inputs

Behavior replay compares the same generated input on two Git revisions. After CI passes, choose a public API scenario to investigate. The report records the reproducing input and both observed results. A reviewer decides which behavior the contract calls for. [Click stdin regression](../evaluation/click-replay.md)

## Probe a public API and export a pytest test

Use a current Python 3.11+ on Linux or macOS, Git, and the dependencies needed to run the scenario on both revisions. Use trusted revisions. The CLI exports both commits to temporary directories and executes the selected modules. For a PR, use its merge base and exact head.

A trusted, self-contained probe defines one `probe(value)` function with a supported type annotation. It sets up the call and returns a small JSON value that captures the observable behavior. Name the project modules it exercises with `--probe-module`. Repeat the flag for multiple modules. Undertow uses the flat and `src/` import roots and verifies that each declared module is loaded from the corresponding Git snapshot. Use concrete modules such as `click.testing` for packages with namespace-only parents. [Click probe](../evaluation/probes/click_stdin.py)

```sh
python3 -IB actions/behavior-replay/discover.py \
  --repo /path/to/project --base "$BASE_SHA" --head "$HEAD_SHA" \
  --probe path/to/probe.py --probe-module myproject.cli --output .undertow/probe-run

python3 -IB actions/behavior-replay/write_test.py \
  --report .undertow/probe-run/report.json --probe path/to/probe.py \
  --expect base --output .undertow/test_behavior_regression.py
```

Choose `--expect base` when the existing behavior is the reviewed contract or `--expect head` when the new behavior is intentional. The exporter binds the test to the probe digest in the finding. It copies the probe and adds a pytest assertion for the chosen outcome. Use `--finding EVIDENCE_ID` when selecting from multiple findings. Run the resulting test against both revisions before adding it to your suite. [Generated example](../evaluation/results/click-probe/test_undertow_stdin.py)

Supply full commit IDs and choose a fresh output directory. The CLI writes `report.json` and `summary.md`. It exits zero when comparisons finish, one when a comparison is unavailable, and two for invalid arguments.

## Dispatch from GitHub Actions

Copy the example to `.github/workflows/behavior-replay.yml` on the default branch. Add the dependencies needed by the scenario. When the candidate's CI has passed, dispatch the workflow from a reviewed branch containing the probe. Enter the full base and candidate SHAs, the path to the probe on the reviewed branch, and the project modules it uses. The candidate checkout must contain both commits. Use the local CLI if the necessary commits are in different repositories. [Manual workflow](examples/behavior-replay.yml)

The workflow stores the Markdown summary and JSON evidence in Actions. Download the report and run the exporter with the same probe after reviewing the observed outcomes. Keep the replay job ephemeral, read-only and free of secrets and persisted checkout credentials. [Showcase run](https://github.com/alpercandemir/undertow-showcase/actions/workflows/behavior-replay.yml)

## Coverage

A probe takes one positional argument annotated `int`, `float`, `Decimal`, or a list of one of these types. The input generator tries small numeric samples and repeated list values. To try direct-function discovery on a specific diff, omit the probe flags. Direct discovery examines at most twelve modified modules and six eligible functions in a flat or conventional `src/` layout, and also tries literal numeric comparison boundaries.

For a finding, the generated input must produce stable outcomes in fresh processes and the difference must reproduce with that input alone. Replay continues through later candidates after a difference fails isolation, within its time budget. Supported results include small scalars, arrays, and dictionaries. A return becoming an exception is reported as a new exception for that input. Import failures, timeouts, and cases without comparable results are explained in the Actions summary.
