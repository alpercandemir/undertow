"""Observe how request construction handles an existing query string."""

import httpx


CASES = {
    "Use the URL as supplied": None,
    "Empty query parameters": {},
    "Supply a replacement query": {"page": "2"},
}


def probe(params):
    request = httpx.Request("GET", "https://example.invalid/?a=1", params=params)
    return str(request.url)


def test_undertow_regression():
    import json

    expected = 'https://example.invalid/'
    observed = probe({})
    assert json.dumps(observed, sort_keys=True, allow_nan=False) == json.dumps(
        expected, sort_keys=True
    )
