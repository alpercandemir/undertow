# Does replay change a pytest export review?

Undertow lets a reviewer export a test for the base or head outcome. This pilot asks whether replay gives the Undertow maintainer a reason to change a real PR, add a test, or explicitly confirm a contract. It measures use in this repository. Adoption by another team is a separate question.

## Which reviews count

On November 25, 2026, PR #20 was the latest PR and the open queue was empty. Take the next ten trusted, non-draft PRs to `alpercandemir/undertow`'s `main` whose diff changes `actions/behavior-replay/write_test.py` or `actions/behavior-replay/run_cases.py` and whose ordinary CI passes. Enroll once per PR at its first eligible head, in order of qualifying CI completion. Record the exact head, its merge base, and the CI run. A rerun or revision of an enrolled PR stays in the same case.

The [fixed probe](probes/undertow_pytest_export.py) gives the real exporter a report in which an API changes an integer into a boolean. It invokes both expectation choices across the generated inputs, executes the emitted assertion with each API behavior, and records which behaviors pass. Python considers `0 == False` and `1 == True`, so these inputs expose assertions that lose the JSON type. The [manual workflow](../.github/workflows/pytest-export-pilot.yml) pins the replay engine to `7f304ffb2087ee5c5db87ee3ed599f5b0c66e28e` and checks the probe digest. Keep both fixed through case ten.

## Run one review

1. Review the PR normally. Before running replay or reading its artifact, record the reviewer, time, findings, and intended next action, even if the plan is simply to merge. A dated review or a local note can serve as the baseline.
2. From the Undertow checkout, inspect the checks and file list. Use the displayed base tip and head for `REVIEWED_BASE_TIP` and `GREEN_HEAD_SHA`. Fetch the commits, compute their merge base, and dispatch from the trusted default branch once the manual workflow is installed.

   ```sh
   gh pr view "$PR" --json baseRefOid,headRefOid,files,statusCheckRollup
   git fetch --no-tags origin main "refs/pull/$PR/head"
   BASE_SHA=$(git merge-base "$REVIEWED_BASE_TIP" "$GREEN_HEAD_SHA")
   gh workflow run pytest-export-pilot.yml --ref main \
     -f base="$BASE_SHA" -f head="$GREEN_HEAD_SHA"
   ```

   For a local run, use the same probe and the same engine. Prepare a clean runner once. Use a fresh output directory for each attempt.

   ```sh
   git clone --quiet --no-checkout . .undertow/export-pilot-runner
   git -C .undertow/export-pilot-runner checkout --quiet --detach 7f304ffb2087ee5c5db87ee3ed599f5b0c66e28e
   ```

   ```sh
   python3 -IB .undertow/export-pilot-runner/actions/behavior-replay/discover.py \
     --repo . --base "$BASE_SHA" --head "$GREEN_HEAD_SHA" \
     --probe evaluation/probes/undertow_pytest_export.py --probe-module write_test \
     --output ".undertow/export-pilot/$PR/$GREEN_HEAD_SHA"
   ```

3. Save the summary and JSON report with the case. Ask the same reviewer what changed after seeing the input and both outcomes. Record the actual action and link to the test, fix, or explicit contract decision. Record unchanged decisions, unavailable comparisons, and missing reviewer responses separately. If an eligible PR merges before the baseline is captured, keep its slot and mark the assessment missing. A changed head needs its own green CI and baseline before another replay.

## Live cases

Registration opened with **0 of 10** cases. Append one row per enrolled PR. Include a timestamp or a link to the original before-replay note. Record hands-on minutes for review and probe upkeep separately from runner time.

| PR, base, head, CI | Before replay: reviewer, time, findings, intended action | Replay status, evidence, runner time | After replay: decision and action link | Review / upkeep minutes |
| --- | --- | --- | --- | --- |

After ten, count decisions with linked follow-through on distinct PRs and report the time spent, unavailable runs, and missing assessments. Two or more changed decisions justify asking the owner whether the measured cost warrants automatic invocation for these paths. One supports keeping the probe on demand. With zero, close the pilot and leave the recurring check out.

## Calibration outside the live sample

On November 25 the pinned exporter compared with itself produced `no_observed_difference`. In a throwaway copy, replacing the generated JSON comparison with `assert observed == expected` made the test exported with `--expect base` accept both integer zero and boolean false. The existing regression suite on that copy still completed with 58 tests and two expected failures. The pilot found input `0`. A test exported from that finding passed on the pinned revision and failed on the seeded revision. This was a deliberately introduced change. No reviewer decision was involved.

Initial setup took at least 102 seconds from the first probe-file write through the first seeded report. That interval excludes earlier design and later verification. The full initial labor cost was not timed.
