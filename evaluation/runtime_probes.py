"""Reviewed observations for the runtime verification trial's pinned revisions."""

import json
from pathlib import Path
import sys


def shipping(values):
    from checkout import shipping_fee
    from delivery import qualifies_for_free_delivery
    from gift_checkout import gift_checkout_total

    subtotal = values["order_total"]
    return {
        str(total): {
            "eligible": qualifies_for_free_delivery(total),
            "shipping": shipping_fee(total),
            "gift_total": gift_checkout_total(total),
        }
        for total in (subtotal - 1, subtotal, subtotal + 1)
    }


def storefront(values):
    from storefront import app

    client = app.test_client()
    app.testing = True
    order_total = values["order_total"]

    def body(response):
        if response.status_code != 200 or not response.is_json:
            raise RuntimeError(f"Unexpected response to {response.request.path}: {response.status_code}.")
        return response.get_json()

    bulk = client.post("/bulk-quotes", json={"totals": [order_total]})
    gift = client.post("/gift-quotes", json={"order_total": order_total})
    return {
        "quote": body(client.get(f"/orders/{order_total}/quote")),
        "return": body(client.post("/refunds", json={
            "days_since_delivery": values["days_since_delivery"], "receipt_attached": True,
        })),
        "day_7_no_receipt_refund": body(client.post("/refunds", json={
            "days_since_delivery": 7, "receipt_attached": False,
        })),
        "installments": body(client.get(f'/installments/{values["months"]}')),
        "delivery_retry": body(client.get(f'/deliveries/{values["attempts"]}/retry')),
        "bulk_quote": {"status": bulk.status_code, "body": bulk.get_json()},
        "gift_quote": {"status": gift.status_code, "body": gift.get_json()},
    }


def command_name(_):
    import click
    from click.testing import CliRunner

    @click.command()
    def my_sync_command():
        pass

    @click.command()
    def my_data_command():
        pass

    @click.command("")
    def explicit_name():
        pass

    return {"name": my_sync_command.name,
            "help": CliRunner().invoke(my_sync_command, ["--help"]).output,
            "data_command_name": my_data_command.name, "explicit_empty_name": explicit_name.name}


def zsh_completion(_):
    from click.shell_completion import CompletionItem, ZshComplete

    formatter = ZshComplete(None, {}, "trial", "_TRIAL_COMPLETE")
    return {"with_help": formatter.format_completion(CompletionItem("a:b", help="some help")),
            "without_help": formatter.format_completion(CompletionItem("a:b"))}


def missing_option(_):
    import click
    from click.testing import CliRunner

    command = click.Command("cli", params=[click.Option(
        ["--option"], show_envvar=True, required=True,
    )])

    def reject(_ctx, _param, _value):
        raise click.BadParameter("bad input")

    @click.command()
    @click.option("--option", show_envvar=True, callback=reject)
    def invalid_option(option):
        pass

    runner = CliRunner()
    missing = runner.invoke(command, [])
    invalid = runner.invoke(invalid_option, ["--option", "bad"])
    return {"missing": {"exit_code": missing.exit_code, "output": missing.output},
            "invalid": {"exit_code": invalid.exit_code, "output": invalid.output}}


def json_body(_):
    import httpx

    compact = httpx.Request("POST", "https://example.invalid", json={"test": 123})

    def observe(value):
        try:
            request = httpx.Request("POST", "https://example.invalid", json={"key": value})
        except ValueError as error:
            return {"error": type(error).__name__, "message": str(error)}
        return {"body": request.content.decode("utf-8"), "hex": request.content.hex()}

    return {"compact": {"body": compact.content.decode(), "length": compact.headers["content-length"]},
            "accented": observe("á"), "infinity": observe(float("inf")), "nan": observe(float("nan"))}


def text_stream(_):
    import httpx
    from httpx._decoders import LineDecoder

    response = httpx.Response(200, content=iter((b"Hello,", b" world!")))
    split = httpx.Response(200, content=iter((b"\xe2", b"\x82", b"\xac")))
    return {"line_decoder_empty": LineDecoder().decode(""),
            "iter_text": list(response.iter_text()), "split_utf8": list(split.iter_text())}


def header_refactor(_):
    import httpx

    response = httpx.Response(200, headers={"link": '<https://example.invalid/next>; rel="next"'})
    return {"links": response.links,
            "header_values": httpx.Headers([("X-Value", "one"), ("X-Value", "two")]).get_list("x-value")}


def choice_help(_):
    import click
    from click.testing import CliRunner

    def help_for(choices, show_choices):
        command = click.Command("cli", params=[click.Option(
            ["--choice"], type=click.Choice(choices), show_choices=show_choices,
        )])
        result = CliRunner().invoke(command, ["--help"])
        if result.exception:
            return {"error": type(result.exception).__name__}
        return {"output": result.output, "exit_code": result.exit_code}

    return {"show_strings": help_for(["foo", "bar"], True),
            "hide_strings": help_for(["foo", "bar"], False),
            "hide_integers": help_for([1, 2], False)}


def context_exit(_):
    import click
    from click.exceptions import Exit

    calls = []
    context = click.Context(click.Command("cli"))
    context.call_on_close(lambda: calls.append("closed"))
    try:
        context.exit(3)
    except Exit as error:
        return {"exit_code": error.exit_code, "callbacks": calls}
    raise RuntimeError("Context.exit returned without raising Exit.")


def typed_flag(_):
    import click
    from click.testing import CliRunner

    def observed(type_, default, args=("--foo",), env=None):
        values = []

        @click.command()
        @click.option("--foo", is_flag=True, type=type_, envvar="TEST_FLAG", **default)
        def cli(foo):
            values.append(foo)
            click.echo(foo)

        result = CliRunner().invoke(cli, list(args), env=env)
        return {"flag_value": cli.params[0].flag_value, "values": values, "output": result.output,
                "exit_code": result.exit_code}

    return {"bool": observed(bool, {}), "bool_default_true": observed(bool, {"default": True}),
            "string": observed(str, {}), "env_false": observed(bool, {}, (), {"TEST_FLAG": "false"})}


def nested_completion(_):
    from click import Command, Group, Option
    from click.shell_completion import ShellComplete

    leaf = Command("data", params=[Option(["-a"])])
    full = Group("full", commands=[leaf], params=[Option(["--verbose"])])
    cli = Group("cli", commands=[Group("get", commands=[full])])

    def complete(args, word):
        completion = ShellComplete(cli, {}, "cli", "_CLICK_COMPLETE")
        return [item.value for item in completion.get_completions(args, word)]

    return {"leaf": complete(["get", "full"], ""),
            "group_options": complete(["get", "full"], "-"),
            "leaf_options": complete(["get", "full", "data"], "-")}


def parameter_hints(_):
    import click

    hints = ["--foo", "-f"]
    return {"invalid": click.BadParameter("bad input", param_hint=hints).format_message(),
            "missing": click.MissingParameter(param_hint=hints, param_type="option").format_message()}


def enum_default(_):
    from enum import Enum
    import click
    from click.testing import CliRunner

    class Color(Enum):
        RED = 1

    command = click.Command("cli", params=[click.Option(
        ["--color"], type=click.Choice(Color), default=Color.RED, show_default=True,
    )])
    result = CliRunner().invoke(command, ["--help"])
    return {"output": result.output, "exit_code": result.exit_code}


def stdin_iteration(_):
    import click
    from click.testing import CliRunner

    @click.command()
    @click.argument("stream", type=click.File("r"))
    def cli(stream):
        for line in stream:
            click.echo(line, nl=False)

    result = CliRunner().invoke(cli, ["-"], input="test\n")
    return {"output": result.output, "exit_code": result.exit_code}


def request_timeout(_):
    import asyncio
    import httpx

    async def send():
        def observe(request):
            return httpx.Response(200, json=request.extensions)

        async with httpx.AsyncClient(transport=httpx.MockTransport(observe), timeout=1e-6) as client:
            request = httpx.Request("GET", "https://example.invalid/slow_response")
            fresh = (await client.send(request)).json()
        with httpx.Client(transport=httpx.MockTransport(observe), timeout=1) as first:
            request = httpx.Request("GET", "https://example.invalid/slow_response")
            first_use = first.send(request).json()
        with httpx.Client(transport=httpx.MockTransport(observe), timeout=None) as second:
            return {"fresh": fresh, "first_use": first_use, "reuse": second.send(request).json()}

    return asyncio.run(send())


def socks_scheme(_):
    import httpx

    try:
        with httpx.Client(proxy="socks5h://localhost/", trust_env=False) as client:
            transport = client._transport_for_url(httpx.URL("https://example.invalid/"))
            return {"pool": type(transport._pool).__name__}
    except ValueError as error:
        return {"error": str(error)}


def request_query(_):
    import httpx

    return {str(params): str(httpx.Request("GET", "https://example.invalid/?a=1", params=params).url)
            for params in (None, {}, {"b": "2"})}


def query_encoding(_):
    import httpx

    return {"raw_query": httpx.URL("https://example.invalid/?a=b c&d=e/f").raw_path.decode(),
            "structured": httpx.URL("https://example.invalid/", params={"a": "b c", "d": "e/f"}).raw_path.decode()}


def deferred_imports(_):
    import httpx

    def modules():
        return {name: name in sys.modules for name in ("certifi", "httpcore")}

    after_import = modules()
    with httpx.Client(trust_env=False):
        return {"after_import": after_import, "after_constructing_client": modules()}


def bytes_method(_):
    import httpx

    method = httpx.Request(b"get", "https://example.invalid/").method
    return {"type": type(method).__name__, "value": method.decode() if isinstance(method, bytes) else method}


PROBES = {
    "showcase-5": ("src", shipping),
    "showcase-6": ("src", storefront),
    "click-2604": ("src", command_name),
    "click-2846": ("src", zsh_completion),
    "click-2972": ("src", missing_option),
    "httpx-2998": (".", text_stream),
    "httpx-3367": (".", json_body),
    "httpx-3387": (".", header_refactor),
}
TRIAL_CASES = tuple(PROBES)
PROBES.update({
    "click-2365": ("src", choice_help),
    "click-2680": ("src", context_exit),
    "click-2930": ("src", typed_flag),
    "click-2934": ("src", stdin_iteration),
    "click-2935": ("src", nested_completion),
    "click-2990": ("src", parameter_hints),
    "click-3004": ("src", enum_default),
    "httpx-3116": (".", request_timeout),
    "httpx-3178": (".", socks_scheme),
    "httpx-3364": (".", request_query),
    "httpx-3373": (".", query_encoding),
    "httpx-3377": (".", deferred_imports),
    "httpx-3378": (".", bytes_method),
})


def main():
    case_id, snapshot, raw_inputs = sys.argv[1:]
    source_dir, probe = PROBES[case_id]
    source = Path(snapshot) / source_dir
    sys.path.insert(0, str(source))
    print(json.dumps(probe(json.loads(raw_inputs)), ensure_ascii=True, allow_nan=False))


if __name__ == "__main__":
    main()
