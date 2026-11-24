"""Observe the body of a public JSON request."""

import httpx


def probe(value: int):
    payload = {"text": "caf\u00e9", "value": float("nan") if value < 0 else value}
    request = httpx.Request("POST", "https://example.invalid/", json=payload)
    return {"content": request.content.decode("utf-8"), "length": request.headers["Content-Length"]}
