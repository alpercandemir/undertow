# Does a maintainer reuse a probe on another PR?

This prospective pilot is open to maintainers of two Python projects. Each project chooses one public API it expects to change and one maintainer who reviews it. A maintainer can opt in by opening an issue in Undertow with the project, API, and paths that qualify a PR. Share only material the project can publish. At publication, enrollment is open and there are no external results.

## Run the next ten qualifying reviews

1. Before selecting PRs, each maintainer writes a reusable probe for their API, records its revision, and defines which changed paths qualify. Record the time to prepare it. Enroll the next five trusted PRs per project that touch those paths and pass normal CI.
2. Review each PR normally. Record the planned action before opening replay and whether an ordinary pytest assertion already answers the question. Run the same probe and record its result, including unavailable comparisons and differences already covered in review. Record any change to the probe and the time it took.
3. Record the actual decision after replay. Link any resulting test, fix, or explicit compatibility decision. Record who initiated a second run. A coordinator’s reminder and a maintainer’s own request are distinct observations.

Use one row per PR. A missed review keeps its slot. Keep private evidence with the participating project.

| Project and PR | Base and head | Planned action before replay | Replay outcome | Decision and evidence | Setup, review and upkeep minutes | Who requested the run |
| --- | --- | --- | --- | --- | --- | --- |

After ten reviews, report linked actions, voluntary repeat use, time spent, and missing assessments. Ask each maintainer whether they would keep the probe and why. Use their answers to decide whether a recurring check on those paths is worth building. The existing internal exporter pilot has its own enrollment rules and case log. [Internal pilot](pytest-export-pilot.md)
