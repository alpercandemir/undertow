import json


def inventory_response(on_hand: int, reserved: int) -> str:
    available = max(on_hand - reserved, 0)
    return json.dumps({"available": available})


def listing_text(payload: str) -> str:
    quantity = json.loads(payload)["available"]
    if type(quantity) is not int:
        return "Check availability"
    if quantity == 0:
        return "Sold out"
    return f"{quantity} in stock"
