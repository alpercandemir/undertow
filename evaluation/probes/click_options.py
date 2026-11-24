"""Observe CLI option parsing and help using Click's public API."""

import click
from click.testing import CliRunner


def probe(values: list[int]):
    @click.command("report")
    @click.option("--format", type=click.Choice(["short", "long"], case_sensitive=False),
                  default="short", show_default=True)
    @click.option("--count", type=click.IntRange(1, 3), default=1, show_default=True)
    @click.option("--enabled/--disabled", envvar="REPORT_ENABLED", default=False, show_envvar=True)
    @click.argument("items", nargs=-1, type=int)
    def report(format, count, enabled, items):
        click.echo(f"{format} {count} {enabled} {items}")

    value = values[0]
    scenarios = [
        ["--help"],
        ["--format", "LONG", *map(str, values)],
        ["--count", str(value), *map(str, values)],
        ["--disabled", *map(str, values)],
        ["--format", "unknown", *map(str, values)],
    ]
    variable = ["false", "true", "0"][len(values) - 1]
    result = CliRunner().invoke(report, scenarios[value % len(scenarios)],
                                env={"REPORT_ENABLED": variable}, terminal_width=80)
    return {"exit_code": result.exit_code, "output": result.output.splitlines()}
