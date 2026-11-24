# Generated regression test for Click #2934

Click changed how `CliRunner` handles end of input in PR #2934. The change caused a command that iterates over a `click.File` backed by stdin to abort at normal EOF. The upstream issue reported the regression and PR #2940 fixed it. This is a retrospective, probe-guided evaluation. We used the reported public API path to write the probe before running Undertow and used the upstream fix to check the result. [Introducing PR](https://github.com/pallets/click/pull/2934) · [Regression report](https://github.com/pallets/click/issues/2939) · [Upstream fix](https://github.com/pallets/click/pull/2940)

## What changed

We ran each revision's own test suite locally on macOS with Python 3.12 and pytest 8.3.5, with external pytest plugins disabled. The items skipped by the suite and its expected failure remain in the counts. The generated test ran separately after the standard suite.

| Run | Base `cfa6f4a` | Regression `262bdf0` | Fix `2a0e3ba` |
| --- | --- | --- | --- |
| Existing suite | 737 passed, 22 skipped, 1 xfailed | 738 passed, 22 skipped, 1 xfailed | 1,284 passed, 22 skipped, 1 xfailed |
| Exit status for one generated line | 0 | 1 | 0 |
| Stdout | `1\n` | `1\n\nAborted!\n` | `1\n` |
| Exported pytest test using the reviewed base expectation | Pass | Fail | Pass |

The probe defines the CLI setup and records exit status and text. Undertow generated the one-line input from the probe's `list[int]` domain and reproduced the difference in fresh processes, including a run of that input alone. The base-to-fix control produced no difference across the tried inputs. Automatic direct-function discovery leaves this case outside its scope because the change is inside a class method. [Probe](probes/click_stdin.py) · [Replay report](results/click-probe/report.json) · [Fixed revision control](results/click-probe/fixed-control.json) · [Generated test](results/click-probe/test_undertow_stdin.py)

We also ran the generated test with the full suites. On the regressed revision, the 738 existing tests still passed and the new test was the sole unexpected failure. The expanded suites passed on base and fix.

## Design choices

- Differential testing uses one generated input across comparable implementations or revisions. A difference supplies evidence to review. The project contract supplies the expected result for a permanent assertion. [McKeeman, Differential Testing for Software](https://www.mrynet.com/FTP/os/DEC/dtj-v10-01_1998.pdf)
- Hypothesis offers equivalence properties and shrinking around an author-supplied input domain. DSpot amplifies existing tests by varying inputs and assertions. Undertow currently uses bounded, deterministic inputs and a trusted public API probe. The Click result measures this narrower approach on one known regression. [Hypothesis Ghostwriter](https://hypothesis.readthedocs.io/en/latest/ghostwriter.html) · [DSpot study](https://arxiv.org/abs/1811.08330)
- Diffblue uses coverage information to prune generated Java tests. Undertow's CI gate establishes that the existing suite passed. Its evidence IDs suppress exact duplicates. Coverage beyond the suite and the materiality of an observation still require review. [Diffblue coverage optimization](https://cover-docs.diffblue.com/features/cover-cli/writing-tests/test-coverage-optimizations)

## Reproduce

From the Undertow repository root, create a fresh workspace with Git, Python 3.12 and pytest 8.3.5. Run public, trusted revisions and a trusted probe.

```sh
trial="$PWD/.undertow/click-probe"
mkdir -p "$trial"
git clone --bare https://github.com/pallets/click.git "$trial/click.git"
uv venv --python 3.12 "$trial/venv"
uv pip install --python "$trial/venv/bin/python" 'pytest==8.3.5'

base=cfa6f4ad3e0078db43f866246b483544afa33ed3
head=262bdf02288daf9a37b9e815b44acafd62b5c6fe
fixed=2a0e3ba907927ade6951d5732b775f11b54cb766
for side in base head fixed; do
  case "$side" in
    base) revision="$base" ;;
    head) revision="$head" ;;
    fixed) revision="$fixed" ;;
  esac
  mkdir "$trial/$side"
  git -C "$trial/click.git" archive "$revision" | tar -x -C "$trial/$side"
done

for side in base head fixed; do
  (cd "$trial/$side" && PYTHONPATH=src PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
    "$trial/venv/bin/python" -m pytest -q)
done

"$trial/venv/bin/python" -IB actions/behavior-replay/discover.py \
  --repo "$trial/click.git" --base "$base" --head "$head" \
  --probe evaluation/probes/click_stdin.py --output "$trial/replay"
"$trial/venv/bin/python" -IB actions/behavior-replay/write_test.py \
  --report "$trial/replay/report.json" --probe evaluation/probes/click_stdin.py \
  --expect base --output "$trial/test_undertow_stdin.py"

for side in base head fixed; do
  test_exit=0
  (cd "$trial/$side" && PYTHONPATH=src PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
    "$trial/venv/bin/python" -m pytest -q "$trial/test_undertow_stdin.py") || test_exit=$?
  case "$side" in
    head) test "$test_exit" -eq 1 || exit 1 ;;
    *) test "$test_exit" -eq 0 || exit 1 ;;
  esac
done
```

The standalone test passes on base and fix and fails on the regression. To reproduce the differential control, rerun the discovery command with `--head "$fixed"` and a fresh output directory.
