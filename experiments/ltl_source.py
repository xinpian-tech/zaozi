"""Parse the model's raw LTL fragment, never a complete UT or JSON envelope.

This lexical contract check is not an execution sandbox. The existing isolated
compiler, lowered-IO validation and native witness replay remain mandatory.
"""
import json
import re

LABEL = re.compile(r"[a-z][a-z0-9_]{0,79}\Z")
FORBIDDEN = {
    'import', 'package', 'class', 'object', 'trait', 'enum', 'given', 'summon',
    'Assume', 'Assert', 'Cover', 'Contract', 'Require', 'Ensure',
    'ImportedDut', 'RunParameter', 'RunIO', 'RunProbe', 'ClockScope', 'ResetScope',
    'dut', 'Reg', 'Wire', 'Mem', 'Memory', 'JasperGold', 'UTGenerator', 'UTExperiment',
}


def tokens(source):
    """Keep token positions for actionable diagnostics; skip comments/literals safely."""
    out = []
    i = 0
    while i < len(source):
        start = i
        if source[i].isspace():
            i += 1
            continue
        if source.startswith('//', i):
            end = source.find('\n', i)
            i = len(source) if end < 0 else end + 1
            continue
        if source.startswith('/*', i):
            depth = 1
            i += 2
            while i < len(source) and depth:
                if source.startswith('/*', i):
                    depth += 1
                    i += 2
                elif source.startswith('*/', i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            if depth:
                raise ValueError(f'unclosed LTL comment at character {start}')
            continue
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            if end < 0:
                raise ValueError(f'unclosed LTL string at character {start}')
            i = end + 3
            out.append(('literal', source[start:i], start))
            continue
        if source[i] in ('"', "'", '`'):
            quote = source[i]
            i += 1
            while i < len(source) and source[i] != quote:
                if source[i] == '\\' and quote != '`':
                    i += 2
                else:
                    i += 1
            if i >= len(source):
                raise ValueError(f'unclosed LTL literal at character {start}')
            i += 1
            kind = 'string' if quote == '"' else 'quoted' if quote == '`' else 'literal'
            out.append((kind, source[start:i], start))
            continue
        match = re.match(r'[A-Za-z_$][A-Za-z0-9_$]*', source[i:])
        if match:
            i += len(match[0])
            out.append(('identifier', match[0], start))
        elif source.startswith(':=', i):
            out.append(('symbol', ':=', i))
            i += 2
        else:
            out.append(('symbol', source[i], i))
            i += 1
    return out


def parse(source):
    if not isinstance(source, str) or not source.strip() or len(source) > 100000:
        raise ValueError('return a nonempty LTL fragment of at most 100000 characters, or STOP')
    if source.strip() == 'STOP':
        return {'stop': True}
    if source.lstrip().startswith(('```', '{"', '{ "')):
        raise ValueError('return raw LTL with Gen(expression, "label"), not Markdown or UT JSON')
    stream = tokens(source)
    stack = []
    pairs = {}
    opens = {'(': ')', '[': ']', '{': '}'}
    for index, (kind, value, position) in enumerate(stream):
        io_field = index >= 2 and stream[index - 1][1] == '.' and stream[index - 2][1] == 'io'
        if ((kind in ('identifier', 'quoted') and value.strip('`') in FORBIDDEN and not io_field) or
                (kind == 'symbol' and value in (':=', '@'))):
            raise ValueError(f'{value} is not part of the LTL output contract (character {position}); the framework owns the UT and wiring')
        if kind == 'identifier' and value in ('val', 'var', 'def') and index + 1 < len(stream):
            if stream[index + 1][1].strip('`') in ('io', 'Gen'):
                raise ValueError('do not redefine the supplied io or Gen')
        if kind != 'symbol':
            continue
        if value in opens:
            stack.append(index)
        elif value in opens.values():
            if not stack or opens[stream[stack[-1]][1]] != value:
                raise ValueError(f'unmatched {value} in LTL at character {position}')
            pairs[stack.pop()] = index
    if stack:
        raise ValueError(f'unclosed {stream[stack[-1]][1]} in LTL at character {stream[stack[-1]][2]}')
    labels = []
    for index, (kind, value, position) in enumerate(stream):
        if kind != 'identifier' or value != 'Gen':
            continue
        if index >= 2 and stream[index - 1][1] == '.' and stream[index - 2][1] == 'io':
            continue  # A DUT port named Gen is not a goal-construction call.
        if index and stream[index - 1][1] == '.':
            raise ValueError('use the supplied unqualified Gen(expression, "label")')
        opening = index + 1
        if opening not in pairs or stream[opening][1] != '(':
            raise ValueError(f'expected Gen(expression, "label") at character {position}')
        closing = pairs[opening]
        commas = []
        cursor = opening + 1
        while cursor < closing:
            if cursor in pairs:
                cursor = pairs[cursor] + 1
                continue
            if stream[cursor][1] == ',' and stream[cursor][0] == 'symbol':
                commas.append(cursor)
            cursor += 1
        if len(commas) != 1 or commas[0] == opening + 1:
            raise ValueError('Gen requires exactly an expression and a literal label')
        label_tokens = stream[commas[0] + 1:closing]
        if len(label_tokens) != 1 or label_tokens[0][0] != 'string':
            raise ValueError('Gen labels must be plain string literals, not computed expressions')
        try:
            label = json.loads(label_tokens[0][1])
        except json.JSONDecodeError as error:
            raise ValueError('Gen labels must be plain snake_case string literals') from error
        if not LABEL.fullmatch(label) or label in labels:
            raise ValueError('Gen labels must be unique safe snake_case identifiers')
        labels.append(label)
    if not 1 <= len(labels) <= 64:
        raise ValueError('return 1..64 Gen goals, or STOP when no new goal remains')
    return {'ltl': source, 'labels': labels}
