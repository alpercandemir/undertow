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

    return {"name": my_sync_command.name,
            "help": CliRunner().invoke(my_sync_command, ["--help"]).output}


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

    def observe(value):
        try:
            request = httpx.Request("POST", "https://example.invalid", json={"key": value})
        except ValueError as error:
            return {"error": type(error).__name__, "message": str(error)}
        return {"body": request.content.decode("utf-8"), "hex": request.content.hex()}

    return {"accented": observe("á"), "infinity": observe(float("inf"))}


def text_stream(_):
    import httpx
    from httpx._decoders import LineDecoder

    response = httpx.Response(200, content=iter((b"Hello,", b" world!")))
    return {"line_decoder_empty": LineDecoder().decode(""),
            "iter_text": list(response.iter_text())}


def header_refactor(_):
    import httpx

    response = httpx.Response(200, headers={"link": '<https://example.invalid/next>; rel="next"'})
    return {"links": response.links,
            "header_values": httpx.Headers([("X-Value", "one"), ("X-Value", "two")]).get_list("x-value")}


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


def main():
    case_id, snapshot, raw_inputs = sys.argv[1:]
    source_dir, probe = PROBES[case_id]
    source = Path(snapshot) / source_dir
    sys.path.insert(0, str(source))
    print(json.dumps(probe(json.loads(raw_inputs)), ensure_ascii=True, allow_nan=False))


if __name__ == "__main__":
    main()
