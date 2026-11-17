# Evidence and validation scope

The active product has implementation tests, executable fixture checks and an authored case gallery. These supply distinct evidence; none automatically establishes independent accuracy, owner approval or human time saved.

## Active checks

- Java tests exercise contract evidence rejection, approved policy/oracle binding, local freshness, MCP read-only boundaries, CLI scope and packaged exploration.
- Python tests exercise generators, expectations, actual Java transport, reduction/replay, adoption, report classification, CI import, hostile archives and approved regression plans.
- The source and packaged smoke paths execute search, replay and generated tests against defects and corrections.
- The eight-case gallery records two successful walkthroughs, five named business-rule violations and one compiler failure. Every violation replays, fails its generated test on the defect and passes the same test plus the full suite on the correction.
- Archive checks verify the complete pre-cleanup source inventory and reject retired implementations or direct dependencies in the packaged JAR.

The [gallery report](https://github.com/alpercandemir/undertow-showcase/actions/runs/44042033765) records an actual GitHub run of the authored cases. The [frozen strategy record](../experiments/behavior-search/strategy-lock.json) and [milestone 3 provenance](../experiments/behavior-search/MILESTONE-3.md) preserve the search experiment's original boundaries. The shared search core was not changed by product cleanup.

## What remains empirical

An independent owner must accept a real component's rule and adapter. An engineer should be able to understand the report and decide from GitHub. Retaining an accepted regression in source and observing its execution on subsequent CI establishes real adoption. Independently selected cases and measured review effort are needed before broader usefulness or accuracy claims.

See the [current review criteria](independent-review.md). Historical platform delivery plans and removed-feature evaluations are in the [archive](../archive/README.md), not current acceptance claims.
