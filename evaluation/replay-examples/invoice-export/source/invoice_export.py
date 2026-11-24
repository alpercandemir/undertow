from dataclasses import dataclass


@dataclass(frozen=True)
class Invoice:
    sequence: int
    invoice_id: str


@dataclass
class Checkpoint:
    sequence: int = 0


class FinanceInbox:
    """In-memory stand-in for a destination that acknowledges a batch prefix."""

    def __init__(self, first_call_capacity: int | None = None):
        self._first_call_capacity = first_call_capacity
        self.invoice_ids: list[str] = []

    def send(self, batch: list[Invoice]) -> int:
        accepted = batch[:self._first_call_capacity]
        self._first_call_capacity = None
        self.invoice_ids.extend(invoice.invoice_id for invoice in accepted)
        return len(accepted)


class InvoiceExporter:
    def __init__(self, source: list[Invoice], inbox: FinanceInbox, checkpoint: Checkpoint):
        self._source = sorted(source, key=lambda invoice: invoice.sequence)
        self._inbox = inbox
        self._checkpoint = checkpoint

    def export_page(self, page_size: int) -> int:
        batch = [
            invoice for invoice in self._source if invoice.sequence > self._checkpoint.sequence
        ][:page_size]
        if not batch:
            return 0

        acknowledged = self._inbox.send(batch)
        if acknowledged:
            self._checkpoint.sequence = batch[acknowledged - 1].sequence
        return acknowledged
