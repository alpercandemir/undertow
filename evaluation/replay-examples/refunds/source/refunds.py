from dataclasses import dataclass


@dataclass(frozen=True)
class RefundWebhook:
    payment_id: str
    refund_id: str
    delivery_id: str
    amount_cents: int


class PaymentLedger:
    def __init__(self, captured_cents: dict[str, int]):
        self._captured_cents = dict(captured_cents)
        self._refunds: dict[str, dict[str, RefundWebhook]] = {
            payment_id: {} for payment_id in captured_cents
        }

    def post_refund(self, event: RefundWebhook, key: str) -> bool:
        entries = self._refunds[event.payment_id]
        if key in entries:
            return False
        remaining = self._captured_cents[event.payment_id] - self.refunded_cents(event.payment_id)
        if not 0 < event.amount_cents <= remaining:
            raise ValueError("Refund exceeds available balance or is nonpositive")
        entries[key] = event
        return True

    def refunded_cents(self, payment_id: str) -> int:
        return sum(event.amount_cents for event in self._refunds[payment_id].values())

    def refund_ids(self, payment_id: str) -> list[str]:
        return [event.refund_id for event in self._refunds[payment_id].values()]


class RefundHandler:
    def __init__(self, ledger: PaymentLedger):
        self._ledger = ledger

    def handle(self, event: RefundWebhook) -> str:
        posted = self._ledger.post_refund(event, event.refund_id)
        return "applied" if posted else "duplicate"
