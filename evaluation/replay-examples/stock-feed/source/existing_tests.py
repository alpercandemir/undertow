import json

from stock_feed import inventory_response, listing_text


def test_available_stock_excludes_reservations():
    assert json.loads(inventory_response(8, 3)) == {"available": 5}


def test_reserved_stock_never_goes_negative():
    assert json.loads(inventory_response(2, 3)) == {"available": 0}


def test_listing_shows_available_quantity():
    assert listing_text(inventory_response(8, 3)) == "5 in stock"


def test_listing_handles_a_sold_out_feed_entry():
    assert listing_text('{"available": 0}') == "Sold out"
