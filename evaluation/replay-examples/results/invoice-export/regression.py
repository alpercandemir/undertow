from invoice_export import Checkpoint, FinanceInbox, Invoice, InvoiceExporter


def probe(first_call_capacity: int) -> dict:
    invoices = [Invoice(sequence, f"INV-{sequence}") for sequence in range(101, 105)]
    inbox = FinanceInbox(first_call_capacity=max(0, first_call_capacity))
    checkpoint = Checkpoint()
    page_size = len(invoices)
    first_ack = InvoiceExporter(invoices, inbox, checkpoint).export_page(page_size)
    cursor_after_first_call = checkpoint.sequence
    retry_ack = InvoiceExporter(invoices, inbox, checkpoint).export_page(page_size)
    return {
        "acknowledged": [first_ack, retry_ack],
        "first_checkpoint": cursor_after_first_call,
        "final_checkpoint": checkpoint.sequence,
        "delivered": inbox.invoice_ids,
    }


def test_undertow_regression():
    import json

    expected = {'acknowledged': [1, 3], 'delivered': ['INV-101', 'INV-102', 'INV-103', 'INV-104'], 'final_checkpoint': 104, 'first_checkpoint': 101}
    observed = probe(1)
    assert json.dumps(observed, sort_keys=True, allow_nan=False) == json.dumps(
        expected, sort_keys=True
    )
