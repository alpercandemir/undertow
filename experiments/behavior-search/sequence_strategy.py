"""Implementation-blind bounded sequences over a declared operation grammar."""

from copy import deepcopy

VERSION = 'declared-sequences-v1'


def validate(case, definition):
    if set(case) != {'initial', 'operations'}:
        raise ValueError('Scenario requires initial and operations')
    lower, upper = definition['initial_range']
    if type(case['initial']) is not int or not lower <= case['initial'] <= upper:
        raise ValueError('Initial value outside declared range')
    operations = case['operations']
    if not isinstance(operations, list) or not 1 <= len(operations) <= 16:
        raise ValueError('Operation count outside declared range')
    actions = {action['name']: action for action in definition['actions']}
    for operation in operations:
        if not isinstance(operation, dict) or operation.get('action') not in actions:
            raise ValueError('Unknown operation')
        fields = actions[operation['action']]['fields']
        if set(operation) != {'action', *fields}:
            raise ValueError('Unexpected operation fields')
        for name, field in fields.items():
            value = operation[name]
            if field['kind'] == 'enum':
                if value not in field['values']:
                    raise ValueError('Value outside declared enumeration')
            elif type(value) is not int or not field['min'] <= value <= field['max']:
                raise ValueError('Integer outside declared range')


def generate(rng, max_length, definition):
    initial = rng.randint(*definition['initial_range'])
    operations = []
    for _ in range(rng.randint(1, max_length)):
        if operations and rng.randrange(3) == 0:
            operations.append(deepcopy(rng.choice(operations)))
            continue
        action = rng.choice(definition['actions'])
        operation = {'action': action['name']}
        for name, field in action['fields'].items():
            if field['kind'] == 'enum':
                value = rng.choice(field['values'])
            else:
                lower, upper = field['min'], field['max']
                choices = [lower, upper, max(lower, min(1, upper)), rng.randint(lower, upper)]
                if field.get('initial_boundaries'):
                    choices += [max(lower, min(upper, initial + delta)) for delta in [-1, 0, 1]]
                previous = [op[name] for op in operations if op['action'] == action['name'] and name in op]
                value = rng.choice(choices + previous)
            operation[name] = value
        operations.append(operation)
    return dict(initial=initial, operations=operations)


def candidates(case, definition):
    """Deletion and equality-preserving simplification, independent of outcomes."""
    operations = case['operations']
    if len(operations) > 1:
        for index in range(len(operations)):
            yield dict(initial=case['initial'], operations=deepcopy(operations[:index] + operations[index + 1:]))
    lower, _ = definition['initial_range']
    for initial in sorted({lower, case['initial'] // 2, case['initial'] - 1}):
        if lower <= initial < case['initial']:
            yield dict(initial=initial, operations=deepcopy(operations))
    actions = {action['name']: action for action in definition['actions']}
    seen = set()
    for index, operation in enumerate(operations):
        for name, field in actions[operation['action']]['fields'].items():
            value = operation[name]
            if field['kind'] == 'enum':
                values = field['values'][:field['values'].index(value)]
            else:
                values = sorted({field['min'], 0, 1, value // 2, value - 1,
                                 case['initial'] - 1, case['initial']})
                values = [item for item in values if field['min'] <= item <= field['max']
                          and abs(item) < abs(value)]
            for replacement in values:
                # Single-field and equal-field changes preserve useful repeated-call relations.
                for group in [False, True]:
                    candidate = deepcopy(case)
                    for other_index, other in enumerate(candidate['operations']):
                        if (other_index == index or group) and other['action'] == operation['action']:
                            if other.get(name) == value:
                                other[name] = replacement
                    signature = repr(candidate)
                    if signature not in seen:
                        seen.add(signature)
                        yield candidate
