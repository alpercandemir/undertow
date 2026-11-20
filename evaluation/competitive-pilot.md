# Reproduce the competitive pilot

Use [the frozen Goal 1 manifest](historical.json) for the 20-PR benchmark and [the supplemental regression](goal4-regression.json) for a separate coverage check. The manifest contains labels. Give tools the pinned trees and diff, and keep the labels, original PR discussions, later commits, and other tool outputs out of their inputs. The `head` is the public merged commit, including squash merges. The `base` is its first parent. The resulting diff includes the landed version of the change.

## Prepare the repositories

```sh
mkdir -p .undertow/eval-repos/pallets .undertow/eval-repos/encode
git clone --bare https://github.com/pallets/click.git .undertow/eval-repos/pallets/click.git
git clone --bare https://github.com/encode/httpx.git .undertow/eval-repos/encode/httpx.git
```

The run commands use Python 3.11+ with `tarfile` data filters (update your 3.11 patch release if needed), Git, and a new output directory per run. Both reviewers can inspect source from the same base/head Git objects. Undertow uses its own region selection and reference tracing. The Copilot CLI benchmark gives the reviewer the full pinned diff and both exported, read-only-by-permission source snapshots for on-demand inspection. The tools choose different excerpts. Neither uses PR metadata, Git history, post-merge context or executed repository tests in this protocol. GitHub Copilot on GitHub is a different surface from the programmatic CLI measured here. Public code may be familiar to a model from training.

## Undertow deterministic and Gemini

```sh
python3 evaluation/run.py run --repos .undertow/eval-repos \
  --output .undertow/pilot-release-local
python3 evaluation/run.py run --repos .undertow/eval-repos \
  --output .undertow/pilot-current-local --working-tree
```

Use `--revision FULL_SHA` instead of `--working-tree` when the candidate worker is committed. Compare `worker_sha256` with the saved record to identify the exact worker. Use `--manifest evaluation/goal4-regression.json` before `run` to score the one-case supplement separately.

For the pending Gemini evaluation, export `GEMINI_API_KEY` via your normal secret mechanism. Choose a provider-supported model and use the same explicit model for both Undertow revisions when the model is compatible with both workers. After running the first command on the frozen release, repeat with `--working-tree` and a new output directory for the current worker. Run the supplement with the same model and settings. Confirm requested versus actual mode. A local fallback or a case with no model invocation is outside the Gemini metric denominators. A failed or skipped call still contributes to cost if billed.

```sh
python3 evaluation/run.py run --repos .undertow/eval-repos \
  --output .undertow/pilot-release-gemini --mode gemini --model "$PILOT_GEMINI_MODEL"
python3 evaluation/run.py run --repos .undertow/eval-repos \
  --output .undertow/pilot-current-gemini --mode gemini --model "$PILOT_GEMINI_MODEL" --working-tree
```

For Gemini cost, reconcile all requests and retries from a dedicated project billing export or record provider `usageMetadata` separately. Apply the dated rates for the actual model and tier to billable input, cached input and output, including thinking tokens where billed. The existing worker reports do not capture token usage. [Gemini Developer API pricing](https://ai.google.dev/gemini-api/docs/pricing)

## GitHub Copilot CLI

Use an authenticated Copilot CLI account with sufficient access and credits. The tested CLI was `1.0.114`. Install it in an ignored directory if needed. The recorded pilot used the account default because explicit selection of the served model was unavailable. The run records preserve the actual model per case. Pass `--model` to pin an available model on a future run and record any resulting difference in configuration.

```sh
npm install --prefix .undertow/pilot-tools --no-package-lock @github/copilot@1.0.114
python3 evaluation/copilot.py --repos .undertow/eval-repos --jobs 3 \
  --copilot .undertow/pilot-tools/node_modules/.bin/copilot --output .undertow/pilot-copilot
python3 evaluation/copilot.py --repos .undertow/eval-repos \
  --manifest evaluation/goal4-regression.json \
  --copilot .undertow/pilot-tools/node_modules/.bin/copilot --output .undertow/pilot-copilot-regression
```

The wrapper runs one fresh session per case with a fixed task prompt, no workspace instructions or built-in MCP servers, and only `view`, `grep`, and `glob`. It keeps raw responses, errors, and usage in the run directory. A timeout, failed request, or missing parseable final answer remains unavailable. Store raw artifacts, separate private manifests, and reviewer responses under `.undertow/`. Use public cases or cases approved for transmission to the provider. [Official Copilot CLI installation](https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/install-copilot-cli) · [Authentication](https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/authenticate-copilot-cli) · [Programmatic CLI reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-programmatic-reference)

## Blind review and scoring

```sh
python3 evaluation/pilot.py blind --output .undertow/pilot-blind \
  --run .undertow/pilot-current-local/run.json --run .undertow/pilot-copilot/run.json
```

Add a Gemini `--run` when available. Generate a separate packet with `--manifest evaluation/goal4-regression.json` and the two supplement runs. The packet assigns random case/finding IDs, uses a common format, and removes explicit product names. Inspect the packet for identity clues and keep `coordinator-key.json` and all scored records private until the assessments are in. Wording or a recognizable public change can still reveal provenance. Give maintainers only `reviewer.json` and the [assessment template](maintainer-assessment.md), following its two-pass protocol.

After unblinding, map each assessment to the case and finding fingerprints in the original run with the coordinator key. A separate adjudication file for each run maps case IDs to finding fingerprints and adjudications, as in the [recorded examples](results/goal4-copilot-adjudications.json). A `correct` verdict requires `changes` (zero or more frozen outcome IDs) and `concrete` (whether the same input and both results are accurate). If a verified material outcome falls outside the labels, leave `changes` empty for that outcome and add a shared ID to `additional_outcomes`. Use the same ID across tools for the same newly identified outcome. Keep original labels fixed. Record final `incorrect` or `unverified` verdicts with the reason. Use `--observed-actions` with a JSON object mapping case IDs to outcome IDs and evidence strings for actual decisions attributable to a finding. The outcome ID is either a frozen label ID or `other/` plus its additional outcome ID. Record predicted decisions and upstream historical fixes separately from actual follow-through attributable to the pilot.

```sh
python3 evaluation/run.py score .undertow/pilot-copilot/run.json \
  --adjudications .undertow/adjudicated-copilot.json
python3 evaluation/pilot.py compare .undertow/pilot-current-local/run.json \
  .undertow/pilot-copilot/run.json --left-adjudications .undertow/adjudicated-local.json \
  --right-adjudications .undertow/adjudicated-copilot.json
```

Precision divides correct findings by all findings with a resolved correctness verdict. Concrete example accuracy divides accurate examples by adjudicated findings that presented an input and both outcomes. Signal quality also includes adjudicated findings without examples in its denominator. Label recall counts distinct matched outcome IDs on fully adjudicated, completed cases. The narrower supported-pattern recall preserves Goal 1's pre-declared `supported` flags. False-positive load uses fully adjudicated, labeled PRs, with a separate rate for controls. Pairwise incremental outcomes are deduplicated within each PR and use only cases completed in the requested modes and fully adjudicated for both tools. The pairwise output lists frozen outcomes missed by both reviewers. Use the independent assessment to find important missing outcomes beyond the labels.

Replay the published comparison without credentials using the saved public run records. To score the supplement, use `--manifest evaluation/goal4-regression.json` before the subcommand and the corresponding `-regression` run and adjudication files.

```sh
python3 evaluation/run.py score evaluation/results/goal4-local.json
python3 evaluation/run.py score evaluation/results/goal4-copilot.json \
  --adjudications evaluation/results/goal4-copilot-adjudications.json
python3 evaluation/pilot.py compare evaluation/results/goal4-local.json \
  evaluation/results/goal4-copilot.json \
  --right-adjudications evaluation/results/goal4-copilot-adjudications.json
```
