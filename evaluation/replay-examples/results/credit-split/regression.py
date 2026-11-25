from credit_split import allocate_credit


def probe(ticket_counts: list[int]) -> dict:
    subtotals = [2500 * count for count in ticket_counts]
    allocations = allocate_credit(1000, subtotals)
    return {"credits_by_group_cents": allocations, "total_credit_cents": sum(allocations)}


def test_undertow_regression():
    import json

    expected = {'credits_by_group_cents': [334, 333, 333], 'total_credit_cents': 1000}
    observed = probe([1, 1, 1])
    assert json.dumps(observed, sort_keys=True, allow_nan=False) == json.dumps(
        expected, sort_keys=True
    )
