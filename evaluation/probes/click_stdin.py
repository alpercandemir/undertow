"""Observe a Click command that consumes stdin through its public file parameter."""

import click
from click.testing import CliRunner


def probe(lines: list[int]):
    @click.command()
    @click.argument("stream", type=click.File("r"))
    def read_stdin(stream):
        for line in stream:
            click.echo(line, nl=False)

    result = CliRunner().invoke(read_stdin, ["-"], input="".join(f"{line}\n" for line in lines))
    return {"exit_code": result.exit_code, "output": result.output}
