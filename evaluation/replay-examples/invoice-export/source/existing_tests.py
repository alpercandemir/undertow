from invoice_export import Checkpoint, FinanceInbox, Invoice, InvoiceExporter


INVOICES = [Invoice(101, "INV-101"), Invoice(102, "INV-102"), Invoice(103, "INV-103")]


def test_full_acknowledgement_advances_checkpoint():
    inbox, checkpoint = FinanceInbox(), Checkpoint()
    assert InvoiceExporter(INVOICES, inbox, checkpoint).export_page(3) == 3
    assert checkpoint.sequence == 103
    assert inbox.invoice_ids == ["INV-101", "INV-102", "INV-103"]


def test_zero_acknowledgements_allow_full_page_retry():
    inbox, checkpoint = FinanceInbox(first_call_capacity=0), Checkpoint()
    assert InvoiceExporter(INVOICES, inbox, checkpoint).export_page(3) == 0
    assert checkpoint.sequence == 0
    assert InvoiceExporter(INVOICES, inbox, checkpoint).export_page(3) == 3
    assert inbox.invoice_ids == ["INV-101", "INV-102", "INV-103"]


def test_restart_resumes_after_a_completed_page():
    inbox, checkpoint = FinanceInbox(), Checkpoint()
    assert InvoiceExporter(INVOICES, inbox, checkpoint).export_page(2) == 2
    assert InvoiceExporter(INVOICES, inbox, checkpoint).export_page(2) == 1
    assert inbox.invoice_ids == ["INV-101", "INV-102", "INV-103"]


def test_empty_page_keeps_checkpoint():
    inbox, checkpoint = FinanceInbox(), Checkpoint(103)
    assert InvoiceExporter(INVOICES, inbox, checkpoint).export_page(3) == 0
    assert checkpoint.sequence == 103
    assert inbox.invoice_ids == []
