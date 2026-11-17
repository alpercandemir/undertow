"""Ticket expectations. The frozen generator receives only the declared grammar."""

import json
from pathlib import Path
import sequence_strategy

HERE = Path(__file__).resolve().parent
DEFINITION = json.loads((HERE / 'ticket-scenario.json').read_text())
NAME = 'ticket-transfer'
MAIN = Path('src/main/java/example/tickets/TicketTransfer.java')
JAVA_FILES = ('ExecutionAdapter.java', 'ScenarioProtocol.java', 'TicketAdapter.java', 'TicketScenarioRunner.java')
ENTRYPOINT = 'experiment.refunds.TicketScenarioRunner'
OBSERVATIONS = ('owner', 'accepted')
ORACLE_FILES = ('ticket_behavior.py', 'ticket-scenario.json')
GENERATOR_VERSION = sequence_strategy.VERSION
TEST_CLASS = 'example.tickets.GeneratedTicketBehaviorTest'
TEST_METHOD = 'previewsAndAcceptanceRespectOwnership'
TEST_FILE = Path('src/test/java/example/tickets/GeneratedTicketBehaviorTest.java')


def validate(case):
    sequence_strategy.validate(case, DEFINITION)


def steps(case):
    return case['operations']


def encode(case):
    operations = ['P' if op['action'] == 'preview' else 'A:' + op['actor'] for op in steps(case)]
    return '\t'.join(['0', *operations]) + '\n'


def generate(rng, max_length):
    return sequence_strategy.generate(rng, max_length, DEFINITION)


def candidates(case):
    return sequence_strategy.candidates(case, DEFINITION)


def evaluate(case, trace):
    validate(case)
    if len(trace) != len(steps(case)) + 1 or trace[0] != {'owner': 0, 'accepted': 0}:
        raise ValueError('Missing ticket observations or invalid reset')
    for step, operation in enumerate(steps(case), 1):
        before, after = trace[step - 1], trace[step]
        expected = dict(status='accepted', owner=before['owner'], accepted=before['accepted'])
        if operation['action'] == 'preview':
            expectation = 'previews_are_read_only'
        elif operation['actor'] != 'recipient':
            expectation = 'only_recipient_accepts'
            expected['status'] = 'rejected'
        elif before['accepted']:
            expectation = 'settled_transfer_owner_stable'
            # A harmless duplicate may be accepted or rejected; neither changes state.
            expected['status'] = after['status']
        else:
            expectation = 'recipient_acceptance_transfers_ownership'
            expected.update(owner=1, accepted=1)
        if after != expected:
            return dict(expectation=expectation, step=step, operation=operation, expected=expected, observed=after)
    return None


def non_vacuity_cases():
    # Discovery, rather than suitability, exercises previews and unauthorized requests.
    return [dict(initial=0, operations=[dict(action='accept', actor='recipient')])]


def summary(case):
    return 'sender owns the ticket; invitation pending; ' + ', '.join(
        op['action'] + (' by ' + op['actor'] if 'actor' in op else '') for op in steps(case))


def junit(case):
    validate(case)
    actions = ', '.join('"' + op['action'] + '"' for op in steps(case))
    actors = ', '.join('"' + op.get('actor', '') + '"' for op in steps(case))
    return '''package example.tickets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/** Local draft from ownership expectations; duplicate return semantics remain open. */
class GeneratedTicketBehaviorTest {
    @Test
    void previewsAndAcceptanceRespectOwnership() {
        var transfer = new TicketTransfer("sender", "recipient");
        assertEquals("sender", transfer.owner(), "reset owner");
        assertEquals(false, transfer.accepted(), "reset invitation");
        String[] actions = {%s};
        String[] actors = {%s};
        for (int index = 0; index < actions.length; index++) {
            String owner = transfer.owner();
            boolean settled = transfer.accepted();
            if (actions[index].equals("preview")) {
                transfer.preview();
                assertEquals(owner, transfer.owner(), "previews_are_read_only owner");
                assertEquals(settled, transfer.accepted(), "previews_are_read_only invitation");
                continue;
            }
            boolean accepted = true;
            try {
                transfer.accept(actors[index]);
            } catch (SecurityException rejected) {
                if (actors[index].equals("recipient")) {
                    throw rejected;
                }
                accepted = false;
            } catch (IllegalStateException rejected) {
                if (!settled) {
                    throw rejected;
                }
                accepted = false;
            }
            if (!actors[index].equals("recipient")) {
                assertEquals(false, accepted, "only_recipient_accepts result");
                assertEquals(owner, transfer.owner(), "only_recipient_accepts owner");
                assertEquals(settled, transfer.accepted(), "only_recipient_accepts invitation");
            } else if (settled) {
                assertEquals(owner, transfer.owner(), "settled_transfer_owner_stable owner");
                assertEquals(settled, transfer.accepted(), "settled_transfer_owner_stable invitation");
            } else {
                assertEquals(true, accepted, "recipient_acceptance_transfers_ownership result");
                assertEquals("recipient", transfer.owner(), "recipient_acceptance_transfers_ownership owner");
                assertEquals(true, transfer.accepted(), "recipient_acceptance_transfers_ownership invitation");
            }
        }
    }
}
''' % (actions, actors)
