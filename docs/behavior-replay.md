# Replay a Python workflow on two Git revisions

Behavior replay compares the same generated input on two Git revisions. After CI passes, choose a public API scenario to investigate. The report records the reproducing input and both observed results. A reviewer decides which behavior the contract calls for. [Click stdin regression](../evaluation/click-replay.md)

## Probe a public API and export a pytest test

Use a current Python 3.11+ on Linux or macOS, Git, and the dependencies needed to run the scenario on both revisions. Use trusted revisions. The CLI exports both commits to temporary directories and executes the selected modules. For a PR, use its merge base and exact head.

For a branch review, install pytest 8+ in the same Python environment. From the Undertow checkout, run the following command with the path to the target repository and your probe.

```sh
python3 -IB actions/behavior-replay/review.py \
  --repo /path/to/project --against main --head my-feature \
  --probe path/to/probe.py --probe-module myproject.api --expect ask
```

`--head` defaults to the target repository's committed HEAD. Both refs must be available locally. The command resolves them once and compares the candidate to the merge base. It prints the first reproducible input and both outcomes. At the prompt, choose `base`, `head`, or `skip`. For a scripted run, pass `--expect base` or `--expect head`. With no expectation, an interactive terminal prompts and a noninteractive run keeps the report.

Each run uses a fresh directory under `.undertow/` in the caller’s working directory. You can supply a new directory with `--output`. The `replay/` subdirectory contains the JSON evidence and Markdown summary. When you choose a side, Undertow runs the same standalone test on both snapshots. It saves `test_undertow_regression.py` if the chosen side passes and the other side fails at the generated assertion with the observed result. The per-side pytest logs and `verification.json` record the checks. A failed verification exits nonzero. The test can then run in your suite independently of Undertow.

### Choose inputs

A trusted, self-contained probe takes one positional argument and returns a small JSON value. For named inputs, declare `CASES` as a top-level dictionary literal in the probe file. Each key is a unique, printable name (1–80 characters). Each value is one JSON-compatible Python literal, such as `None`, `{}`, a string, or a nested list. Supply 1–40 cases, in the order you want reviewed. Reports and exported tests use the actual value. [HTTPX example](../evaluation/probes/httpx_query_params.py)

For generated inputs, annotate the argument as `int`, `float`, `Decimal` or a list of one of those types. These annotations retain their original generated-input behavior, including for older probes that use a private lookup called `CASES`. For named cases, leave the argument unannotated or give it a different annotation. Name the project modules the probe exercises with `--probe-module`. Repeat the flag for multiple modules. Undertow checks that each declared module actually loaded from that revision. Use concrete modules such as `click.testing` for packages with namespace-only parents. [Click example](../evaluation/probes/click_stdin.py)

### Compare exact commits or export a saved report

The lower-level commands accept full commit SHAs and a fresh output directory. They also work with saved reports and numeric probes from earlier runs.

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

The input generator tries small numeric samples and repeated list values. To try direct-function discovery on a specific diff, omit the probe flags when running `discover.py`. Direct discovery examines at most twelve modified modules and six eligible functions in a flat or conventional `src/` layout, and also tries literal numeric comparison boundaries.

For a finding, the generated input must produce stable outcomes in fresh processes and the difference must reproduce with that input alone. Replay continues through later candidates after a difference fails isolation, within its time budget. Supported results include small scalars, arrays, and dictionaries. A return becoming an exception is reported as a new exception for that input. Import failures, timeouts, and cases without comparable results are explained in the Actions summary.
