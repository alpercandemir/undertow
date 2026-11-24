from dataclasses import replace

import pytest

from refunds import PaymentLedger, RefundHandler, RefundWebhook


@pytest.fixture
def payment():
    ledger = PaymentLedger({"payment-1": 12_000, "payment-2": 12_000})
    return ledger, RefundHandler(ledger)


def test_records_partial_refund(payment):
    ledger, handler = payment
    event = RefundWebhook("payment-1", "refund-1", "delivery-1", 4_000)
    assert handler.handle(event) == "applied"
    assert ledger.refunded_cents("payment-1") == 4_000


def test_ignores_redelivery_with_a_new_delivery_id(payment):
    ledger, handler = payment
    event = RefundWebhook("payment-1", "refund-1", "delivery-1", 4_000)
    handler.handle(event)
    assert handler.handle(replace(event, delivery_id="retry-1")) == "duplicate"
    assert ledger.refunded_cents("payment-1") == 4_000


def test_records_two_partial_refunds_of_different_amounts(payment):
    ledger, handler = payment
    assert handler.handle(RefundWebhook("payment-1", "refund-1", "delivery-1", 4_000)) == "applied"
    assert handler.handle(RefundWebhook("payment-1", "refund-2", "delivery-2", 8_000)) == "applied"
    assert ledger.refund_ids("payment-1") == ["refund-1", "refund-2"]
    assert ledger.refunded_cents("payment-1") == 12_000


def test_rejects_an_amount_above_the_remaining_balance(payment):
    ledger, handler = payment
    handler.handle(RefundWebhook("payment-1", "refund-1", "delivery-1", 4_000))
    with pytest.raises(ValueError, match="available balance"):
        handler.handle(RefundWebhook("payment-1", "refund-2", "delivery-2", 9_000))
    assert ledger.refunded_cents("payment-1") == 4_000


def test_equal_amounts_on_separate_payments_are_independent(payment):
    ledger, handler = payment
    for index in (1, 2):
        assert handler.handle(RefundWebhook(
            f"payment-{index}", f"refund-{index}", f"delivery-{index}", 4_000,
        )) == "applied"
        assert ledger.refunded_cents(f"payment-{index}") == 4_000
