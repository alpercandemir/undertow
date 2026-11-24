from dataclasses import dataclass
from datetime import UTC, datetime
from zoneinfo import ZoneInfo


@dataclass(frozen=True)
class Crossing:
    crossing_id: str
    leaves_at: datetime


class DepartureBoard:
    def __init__(self, crossings: list[Crossing], port_timezone: ZoneInfo):
        self._crossings = sorted(crossings, key=lambda crossing: crossing.leaves_at.astimezone(UTC))
        self._port_timezone = port_timezone

    def upcoming(self, as_of: datetime) -> list[dict[str, str]]:
        cutoff = as_of.astimezone(UTC)
        return [
            self._row(crossing)
            for crossing in self._crossings
            if crossing.leaves_at.astimezone(UTC) > cutoff
        ]

    def _row(self, crossing: Crossing) -> dict[str, str]:
        local = crossing.leaves_at.astimezone(self._port_timezone)
        return {"id": crossing.crossing_id, "local_time": local.strftime("%H:%M %Z")}
