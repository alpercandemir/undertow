"""Observe the decoded chunks exposed by the public response iterator."""

import httpx


def probe(values: list[int]):
    chunks = iter(str(value).encode("ascii") for value in values)
    return list(httpx.Response(200, content=chunks).iter_text())
