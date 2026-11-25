import json

from stock_feed import inventory_response, listing_text


def probe(reservations: int) -> dict:
    payload = inventory_response(on_hand=2, reserved=max(0, reservations))
    return {"feed": json.loads(payload), "listing": listing_text(payload)}


def test_undertow_regression():
    import json

    expected = {'feed': {'available': 0}, 'listing': 'Sold out'}
    observed = probe(2)
    assert json.dumps(observed, sort_keys=True, allow_nan=False) == json.dumps(
        expected, sort_keys=True
    )
