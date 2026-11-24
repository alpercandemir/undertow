from datetime import UTC, datetime
from zoneinfo import ZoneInfo

from departure_board import Crossing, DepartureBoard


PORT = ZoneInfo("Europe/London")


def test_board_excludes_departed_crossings_and_sorts_the_rest():
    board = DepartureBoard([
        Crossing("late", datetime(2025, 7, 10, 15, tzinfo=UTC)),
        Crossing("early", datetime(2025, 7, 10, 10, tzinfo=UTC)),
        Crossing("next", datetime(2025, 7, 10, 12, tzinfo=UTC)),
    ], PORT)
    assert [row["id"] for row in board.upcoming(datetime(2025, 7, 10, 11, tzinfo=UTC))] == [
        "next", "late",
    ]


def test_crossing_leaving_at_the_cutoff_is_excluded():
    cutoff = datetime(2025, 1, 10, 10, tzinfo=UTC)
    assert DepartureBoard([Crossing("at-cutoff", cutoff)], PORT).upcoming(cutoff) == []


def test_board_displays_the_ports_local_time_in_summer():
    crossing = Crossing("summer", datetime(2025, 7, 10, 12, tzinfo=UTC))
    result = DepartureBoard([crossing], PORT).upcoming(datetime(2025, 7, 10, 11, tzinfo=UTC))
    assert result == [{"id": "summer", "local_time": "13:00 BST"}]


def test_non_utc_cutoff_represents_the_same_instant():
    board = DepartureBoard([Crossing("next", datetime(2025, 7, 10, 12, tzinfo=UTC))], PORT)
    now = datetime(2025, 7, 10, 11, tzinfo=UTC)
    assert board.upcoming(now) == board.upcoming(now.astimezone(PORT))
