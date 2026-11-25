import pytest

from credit_split import allocate_credit


def test_single_group_receives_the_credit():
    assert allocate_credit(1000, [2500]) == [1000]


def test_credit_is_proportional_to_group_subtotals():
    assert allocate_credit(900, [2500, 5000]) == [300, 600]


def test_zero_credit_leaves_each_group_unchanged():
    assert allocate_credit(0, [2500, 5000]) == [0, 0]


def test_credit_cannot_exceed_the_booking_total():
    with pytest.raises(ValueError):
        allocate_credit(2501, [2500])
