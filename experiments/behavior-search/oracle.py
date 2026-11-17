"""Executable expectation, independent of the Java subject and generator."""

import json
from pathlib import Path

DEFINITION = json.loads(Path(__file__).with_name('scenario.json').read_text())
CAP = 'aggregate_refunds_never_exceed_capture'


def validate(case):
    if set(case) != {'capture', 'amounts'}:
        raise ValueError('Scenario requires capture and amounts')
    capture, amounts = case['capture'], case['amounts']
    if type(capture) is not int or not 2 <= capture <= 10000:
        raise ValueError('Capture outside approved range')
    if not isinstance(amounts, list) or not 1 <= len(amounts) <= 16:
        raise ValueError('Operation count outside approved range')
    if any(type(amount) is not int or not -1 <= amount <= 10001 for amount in amounts):
        raise ValueError('Amount outside approved range')


def evaluate(case, trace):
    """Return the first failed obligation; malformed observations are protocol errors."""
    validate(case)
    if len(trace) != len(case['amounts']) + 1 or trace[0] != {'refunded': 0, 'effects': 0}:
        raise ValueError('Missing observations or reset did not produce empty state')
    for step, amount in enumerate(case['amounts'], 1):
        before, after = trace[step - 1], trace[step]
        valid = 0 < amount <= case['capture'] - before['refunded']
        expected = 'rejected'
        if valid:
            expected = 'accepted'
        expectation = None
        if not 0 <= after['refunded'] <= case['capture']:
            expectation = CAP
        elif after['status'] == 'rejected' and (after['refunded'], after['effects']) != (
                before['refunded'], before['effects']):
            expectation = 'rejected_operation_leaves_state_unchanged'
        elif after['status'] != expected:
            expectation = 'valid_refund_accepted' if valid else 'invalid_refund_rejected'
        elif valid and (after['refunded'], after['effects']) != (
                before['refunded'] + amount, before['effects'] + 1):
            expectation = 'accepted_refund_has_exact_effect'
        if expectation:
            return dict(expectation=expectation, step=step, amount=amount,
                        expected=dict(status=expected,
                                      refunded=before['refunded'] + (amount if valid else 0),
                                      effects=before['effects'] + int(valid),
                                      maximum_refunded=case['capture']), observed=after)
    return None


def non_vacuity_cases():
    # These establish suitability, not discovery: positive partial, exact cap,
    # rejection before/after effects, and reset are all independently observed.
    return [dict(capture=10, amounts=[3, 7]), dict(capture=10, amounts=[10]),
            dict(capture=10, amounts=[0, 11, 1, -1, 9])]


def junit(case):
    """Export assertions from the expectation, never from a failing subject trace."""
    validate(case)
    amounts = ', '.join(str(value) + 'L' for value in case['amounts'])
    return '''package example.refunds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** Local draft from the reviewed experiment expectation; adoption requires owner review. */
class GeneratedRefundBehaviorTest {
    @Test
    void refundsRespectCaptureAndAcceptance() {
        long capture = %dL;
        var service = new RefundService(capture);
        assertEquals(0L, service.refunded(), "reset refunded");
        assertEquals(0, service.effects(), "reset effects");
        long[] amounts = {%s};
        for (int index = 0; index < amounts.length; index++) {
            long amount = amounts[index];
            long before = service.refunded();
            int effects = service.effects();
            boolean valid = amount > 0 && amount <= capture - before;
            boolean accepted = true;
            try {
                service.refund("search-" + (index + 1), amount);
            } catch (IllegalArgumentException rejected) {
                accepted = false;
            }
            assertTrue(service.refunded() >= 0 && service.refunded() <= capture,
                    "aggregate_refunds_never_exceed_capture at step " + (index + 1));
            if (!accepted) {
                assertEquals(before, service.refunded(), "rejection changed refunded");
                assertEquals(effects, service.effects(), "rejection changed effects");
            }
            assertEquals(valid, accepted, "valid refunds accepted; invalid refunds rejected");
            assertEquals(before + (valid ? amount : 0), service.refunded(), "settled amount");
            assertEquals(effects + (valid ? 1 : 0), service.effects(), "settled effects");
        }
    }
}
''' % (case['capture'], amounts)
