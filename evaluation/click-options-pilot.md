# Click option replay pilot

## Frozen protocol

Choose the first six landed first-parent comparisons from Click `main` dated September 13 through December 31, 2025 UTC whose diff touches `src/click/core.py`, `src/click/decorators.py`, `src/click/parser.py`, or `src/click/types.py`. Exclude head commits in the existing historical and stdin manifests. Take each head's first parent as its base. Selection uses paths, commit dates and hashes. The run uses a fixed probe and keeps PR titles, descriptions, and diff contents out of probe and case selection. [Frozen pairs](click-options-pilot.json) · [Shared probe](probes/click_options.py)

Run each revision's existing pytest suite before comparing. Replay only pairs whose suites both pass. Count completed comparisons, unavailable comparisons, and verified differences. After the run, read the changes to judge whether each reported result adds review evidence beyond the upstream PR and tests. An intended change already explained there counts as a repeated observation. An unexpected user-visible change or a previously unshown consequence can warrant a regression test. Cases without observations supply evidence only about the tried inputs. [Runner](click_options_pilot.py)

## Reproduce

Prepare a Click bare clone and install pytest 8.3.5 in a Python 3.12 environment as in the [stdin reproduction](click-replay.md#reproduce). Use a new output directory.

```sh
trial="$PWD/.undertow/click-probe"
"$trial/venv/bin/python" evaluation/click_options_pilot.py \
  --repo "$trial/click.git" --output .undertow/click-options-pilot
```
