# Contributing

Undertow uses Python and Git. The on-demand comparison and test exporter live in `actions/behavior-replay`.

Run the regression suite with Python 3.11+, Git, and `pytest==8.3.5` installed in the test environment. CI runs `python3 -B -m unittest discover -s tests -v` on pull requests and pushes to `main`. The suite uses temporary repositories and mocked API calls.

When changing replay or test export, exercise a known before-and-after case. Inspect the observed input and outcomes, and run the exported assertion against both revisions. Include the commit IDs and probe when reporting a problem.

See the [replay guide](docs/behavior-replay.md), [product benchmark](evaluation/benchmark.md), and [showcase](https://github.com/alpercandemir/undertow-showcase).
