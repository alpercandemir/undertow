from dataclasses import replace

from refunds import PaymentLedger, RefundHandler, RefundWebhook


def probe(items_per_refund: list[int]) -> dict:
    payment_id = "payment-1"
    unit_price_cents = 4_000
    ledger = PaymentLedger({payment_id: 3 * unit_price_cents})
    handler = RefundHandler(ledger)
    statuses = []
    for index, item_count in enumerate(items_per_refund, start=1):
        event = RefundWebhook(
            payment_id, f"refund-{index}", f"delivery-{index}", item_count * unit_price_cents,
        )
        statuses.append(handler.handle(event))
        if index == 1:
            statuses.append(handler.handle(replace(event, delivery_id="retry-1")))

    return {
        "statuses": statuses,
        "refunded_cents": ledger.refunded_cents(payment_id),
        "refund_ids": ledger.refund_ids(payment_id),
    }
