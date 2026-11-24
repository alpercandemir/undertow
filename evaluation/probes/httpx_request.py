"""Observe URL and method construction through the public Request API."""

import httpx


def probe(value: int):
    candidates = {
        0: None,
        1: {},
        -1: [],
        2: {"next": "text with space/leaf"},
        10: [("x", "1"), ("x", "2")],
        100: "a=2",
    }
    method = b"get" if value == -1 else "get"
    request = httpx.Request(method, "https://example.invalid/?a=1", params=candidates[value])
    actual_method = request.method
    return {
        "url": str(request.url),
        "method_type": type(actual_method).__name__,
        "method": actual_method.decode("ascii") if isinstance(actual_method, bytes) else actual_method,
    }
