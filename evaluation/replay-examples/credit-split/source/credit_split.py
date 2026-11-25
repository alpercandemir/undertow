def allocate_credit(credit_cents: int, subtotals: list[int]) -> list[int]:
    """Allocate a booking credit across ticket groups in receipt order."""
    if not subtotals or any(amount <= 0 for amount in subtotals):
        raise ValueError("Each group needs a positive subtotal")
    total = sum(subtotals)
    if not 0 <= credit_cents <= total:
        raise ValueError("Credit must be between zero and the booking total")
    return [credit_cents * amount // total for amount in subtotals]
