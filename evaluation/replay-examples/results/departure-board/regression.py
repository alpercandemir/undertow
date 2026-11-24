from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

from departure_board import Crossing, DepartureBoard


def probe(minutes_after_opening: int) -> dict:
    port = ZoneInfo("Europe/London")
    opening = datetime(2025, 10, 26, 0, 20, tzinfo=UTC)
    now = opening + timedelta(minutes=minutes_after_opening)
    crossings = [
        Crossing("F71", datetime(2025, 10, 26, 1, 30, tzinfo=UTC)),
        Crossing("F72", datetime(2025, 10, 26, 2, 10, tzinfo=UTC)),
    ]
    return {
        "local_time": now.astimezone(port).strftime("%H:%M %Z"),
        "displayed_crossings": [row["id"] for row in DepartureBoard(crossings, port).upcoming(now)],
    }


def test_undertow_regression():
    import json

    expected = {'displayed_crossings': ['F71', 'F72'], 'local_time': '01:30 BST'}
    observed = probe(10)
    assert json.dumps(observed, sort_keys=True, allow_nan=False) == json.dumps(
        expected, sort_keys=True
    )
