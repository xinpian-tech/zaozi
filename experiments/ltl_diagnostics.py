"""Source locations and conservative temporal facts for solver feedback.

These notes are neither an UNSAT core nor an automatic repair. They describe
only a recognized surface form inside the named Gen, without DUT-specific facts.
"""
from copy import deepcopy
import json

from ltl_source import normalize, tokens


def solver_source_notes(errors, source):
    result = deepcopy(errors)
    if not source:
        return result
    body, _ = normalize(source)
    stream = tokens(body)
    pairs, stack = {}, []
    matching = {')': '(', ']': '[', '}': '{'}
    for index, (kind, value, _) in enumerate(stream):
        if kind != 'symbol':
            continue
        if value in ('(', '[', '{'):
            stack.append(index)
        elif value in matching:
            if not stack or stream[stack[-1]][1] != matching[value]:
                return result
            pairs[stack.pop()] = index
    if stack:
        return result
    reverse_pairs = {closing: opening for opening, closing in pairs.items()}

    def left_name(end):
        start = end
        if stream[end][1] == ')':
            start = reverse_pairs[end]
            inner_start, inner_end = start + 1, end - 1
            while (inner_start < inner_end and stream[inner_start][1] == '(' and
                   pairs.get(inner_start) == inner_end):
                inner_start, inner_end = inner_start + 1, inner_end - 1
            if inner_start != inner_end:
                return None
            token = stream[inner_start]
        else:
            token = stream[end]
        return (token, start) if token[0] in ('identifier', 'quoted') else None

    def location(position):
        return dict(line=body.count('\n', 0, position) + 1,
                    column=position - body.rfind('\n', 0, position))

    goals = {}
    for index, (kind, value, position) in enumerate(stream):
        if (kind, value) != ('identifier', 'Gen') or (index and stream[index-1][1] == '.'):
            continue
        opening = index + 1
        closing = pairs.get(opening)
        if closing is None or stream[opening][1] != '(':
            continue
        cursor, commas = opening + 1, []
        while cursor < closing:
            if cursor in pairs:
                cursor = pairs[cursor] + 1
                continue
            if stream[cursor][:2] == ('symbol', ','):
                commas.append(cursor)
            cursor += 1
        if len(commas) != 1 or closing - commas[0] != 2:
            continue
        label = stream[commas[0] + 1]
        if label[0] != 'string':
            continue
        goals[json.loads(label[1])] = (opening + 1, commas[0], location(position))

    for error in result:
        if not isinstance(error, dict) or not str(error.get('code', '')).startswith('jg_goal_'):
            continue
        goal = goals.get(error.get('goal'))
        if goal is None:
            continue
        first, last, start = goal
        error.update(start)
        if error.get('code') != 'jg_goal_infeasible':
            continue
        notes = []
        for index in range(first + 1, last - 2):
            if [s[1] for s in stream[index:index+3]] != ['#', '#', '#']:
                continue
            if body[stream[index][2]:stream[index][2] + 3] != '###':
                continue
            named = left_name(index-1)
            if named is None:
                continue
            left, left_start = named
            # The full left term must be the bare name, not !p, io.p or a
            # compound Boolean expression whose last token happens to be p.
            if (left_start != first and stream[left_start-1][1] != '(' and
                    [s[1] for s in stream[max(first,left_start-3):left_start]] != ['#', '#', '#']):
                continue
            right = index + 3
            while right < last and stream[right][1] == '(':
                right += 1
            if right >= last or stream[right][:2] != left[:2]:
                continue
            tail = [s[1] for s in stream[right+1:right+5]]
            if tail != ['.', '#', '#', '(']:
                continue
            notes.append(dict(code='repeated_name_at_sequence_join',
                **location(stream[index][2]), predicate=left[1],
                message='The same name is used on both sides of this sequence join. If it is a one-cycle '
                        'predicate, this form requires two consecutive occurrences. ### starts the '
                        'right operand one sample after the left endpoint; it does not reuse that '
                        'endpoint. Repetition can be intentional: this is not a type diagnosis or '
                        'proof of the UNSAT cause. Check the intended event count/ordering; no source '
                        'change was applied.'))
            if len(notes) == 4:
                break
        if notes:
            error['source_facts'] = notes
    return result
