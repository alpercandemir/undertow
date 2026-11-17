"""Retry expectation over actual settled state, separate from generation."""

import json
from pathlib import Path
import sequence_strategy

HERE = Path(__file__).resolve().parent
DEFINITION = json.loads((HERE / 'retry-scenario.json').read_text())
NAME = 'refund-retry'
PRIMARY = 'retry_idempotency'
MAIN = Path('src/main/java/example/refunds/RefundService.java')
JAVA_FILES = ('ExecutionAdapter.java', 'ScenarioProtocol.java', 'RefundAdapter.java', 'ScenarioRunner.java')
ENTRYPOINT = 'experiment.refunds.ScenarioRunner'
OBSERVATIONS = ('refunded', 'effects')
ORACLE_FILES = ('retry_behavior.py', 'retry-scenario.json')
GENERATOR_VERSION = sequence_strategy.VERSION
TEST_CLASS = 'example.refunds.GeneratedRefundRetryTest'
TEST_METHOD = 'settledRefundRetriesHaveNoAdditionalEffects'
TEST_FILE = Path('src/test/java/example/refunds/GeneratedRefundRetryTest.java')


def validate(case):
    sequence_strategy.validate(case, DEFINITION)


def steps(case):
    return case['operations']


def encode(case):
    return '\t'.join([str(case['initial']),
                      *[f"F:{op['key']}:{op['amount']}" for op in steps(case)]]) + '\n'


def generate(rng, max_length):
    return sequence_strategy.generate(rng, max_length, DEFINITION)


def candidates(case):
    return sequence_strategy.candidates(case, DEFINITION)


def evaluate(case, trace):
    validate(case)
    if len(trace) != len(steps(case)) + 1 or trace[0] != {'refunded': 0, 'effects': 0}:
        raise ValueError('Missing observations or reset did not produce empty state')
    settled = {}
    for step, operation in enumerate(steps(case), 1):
        before, after = trace[step - 1], trace[step]
        key, amount = operation['key'], operation['amount']
        retry = key in settled and settled[key] == amount
        conflict = key in settled and settled[key] != amount
        valid = not conflict and (retry or 0 < amount <= case['initial'] - before['refunded'])
        expected = dict(status='accepted' if valid else 'rejected', refunded=before['refunded'], effects=before['effects'])
        if valid and not retry:
            expected['refunded'] += amount
            expected['effects'] += 1
        expectation = None
        if retry and after != expected:
            expectation = PRIMARY
        elif conflict and after != expected:
            expectation = 'operation_key_amount_immutable'
        elif not valid and after != expected:
            expectation = 'invalid_refund_rejected_without_effects'
        elif valid and after != expected:
            expectation = 'fresh_refund_has_exact_effect'
        if expectation:
            return dict(expectation=expectation, step=step, operation=operation, expected=expected, observed=after)
        if valid and not retry:
            settled[key] = amount
    return None


def non_vacuity_cases():
    # No retries here: retry failures must be generated discoveries.
    return [dict(initial=10, operations=[dict(action='refund', key=1, amount=3), dict(action='refund', key=2, amount=7)]),
            dict(initial=10, operations=[dict(action='refund', key=1, amount=0),
                                        dict(action='refund', key=2, amount=11), dict(action='refund', key=3, amount=10)])]


def summary(case):
    return f"capture {case['initial']}; " + ', '.join(f"key {op['key']} amount {op['amount']}" for op in steps(case))


def junit(case):
    validate(case)
    keys = ', '.join(str(op['key']) for op in steps(case))
    amounts = ', '.join(str(op['amount']) + 'L' for op in steps(case))
    return '''package example.refunds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Local draft from the retry expectation, never from the buggy output. */
class GeneratedRefundRetryTest {
    @Test
    void settledRefundRetriesHaveNoAdditionalEffects() {
        long capture = %dL;
        var service = new RefundService(capture);
        Map<Integer, Long> settled = new HashMap<>();
        int[] keys = {%s};
        long[] amounts = {%s};
        assertEquals(0L, service.refunded(), "reset refunded");
        assertEquals(0, service.effects(), "reset effects");
        for (int index = 0; index < keys.length; index++) {
            int key = keys[index];
            long amount = amounts[index];
            long before = service.refunded();
            int effects = service.effects();
            boolean retry = settled.containsKey(key) && settled.get(key) == amount;
            boolean conflict = settled.containsKey(key) && settled.get(key) != amount;
            boolean valid = !conflict && (retry || amount > 0 && amount <= capture - before);
            boolean accepted = true;
            try {
                service.refund("search-" + key, amount);
            } catch (IllegalArgumentException rejected) {
                accepted = false;
            }
            String obligation = "fresh_refund_has_exact_effect";
            if (retry) {
                obligation = "retry_idempotency";
            } else if (conflict) {
                obligation = "operation_key_amount_immutable";
            } else if (!valid) {
                obligation = "invalid_refund_rejected_without_effects";
            }
            assertEquals(valid, accepted, obligation + " accepted at step " + (index + 1));
            assertEquals(before + (valid && !retry ? amount : 0), service.refunded(), obligation);
            assertEquals(effects + (valid && !retry ? 1 : 0), service.effects(), obligation);
            if (valid && !retry) {
                settled.put(key, amount);
            }
        }
    }
}
''' % (case['initial'], keys, amounts)
