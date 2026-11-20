# Contributing

Undertow ships as a self-contained GitHub Action. Its source is in `actions/change-review` and uses Python's standard library and Git.

Run the regression suite locally with `python3 -m unittest discover -s tests -v` (Python 3.11+ and Git). CI runs the same command on pushes and pull requests. The suite uses temporary repositories and mocked API calls.

When changing analysis or publishing behavior, run the action on a pull request with a known before-and-after outcome. Review the examples, exact source links and coverage. Include the two commit IDs when reporting a problem.

See the [integration guide](docs/change-review.md), [evaluation protocol and v0.3.0 baseline](evaluation/README.md), and [showcase](https://github.com/alpercandemir/undertow-showcase).
