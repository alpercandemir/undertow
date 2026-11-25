from credit_split import allocate_credit


def probe(ticket_counts: list[int]) -> dict:
    subtotals = [2500 * count for count in ticket_counts]
    allocations = allocate_credit(1000, subtotals)
    return {"credits_by_group_cents": allocations, "total_credit_cents": sum(allocations)}
