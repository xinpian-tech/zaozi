"""Parse the model's raw LTL fragment, never a complete UT or JSON envelope.

This lexical contract check is not an execution sandbox. The existing isolated
compiler, lowered-IO validation and native witness replay remain mandatory.
"""
import json
import re
import hashlib

LABEL = re.compile(r"[a-z][a-z0-9_]{0,79}\Z")
FORBIDDEN = {
    'import', 'package', 'class', 'object', 'trait', 'enum', 'given', 'summon', 'def',
    'Assume', 'Assert', 'Cover', 'Contract', 'Require', 'Ensure',
    'ImportedDut', 'RunParameter', 'RunIO', 'RunProbe', 'ClockScope', 'ResetScope',
    'dut', 'Reg', 'Wire', 'Mem', 'Memory', 'JasperGold', 'UTGenerator', 'UTExperiment',
}


class OutputFormatError(ValueError):
    """Ambiguous or unsupported envelope; never guess which code to execute."""


class LiteralArgumentError(ValueError):
    """A definitely invalid literal constructor in model source, not an EDA failure."""
    def __init__(self, source, position, message):
        super().__init__(message)
        self.diagnostic = {'file':'model.ltl','kind':'literal-argument',
            'code':'ltl_bigint_radix','line':source.count('\n',0,position)+1,
            'col':position-source.rfind('\n',0,position),'message':message}
        self.diagnostics = [self.diagnostic]


def check_bigint_literals(source, stream, pairs):
    """Check only plain ASCII BigInt(text, literalRadix), never evaluate Scala.

    Escaped/interpolated strings, computed arguments and qualified/shadowed
    identifiers stay with the compiler/runtime. No value or radix is repaired.
    """
    if any(value in ('val','var') and index+1<len(stream) and stream[index+1][1]=='BigInt'
           for index,(_,value,_) in enumerate(stream)):
        return
    errors=[]
    for index,(kind,value,position) in enumerate(stream):
        if (kind!='identifier' or value!='BigInt' or
                index and stream[index-1][1]=='.'):
            continue
        opening=index+1
        if opening not in pairs or stream[opening][1]!='(':
            continue
        args=stream[opening+1:pairs[opening]]
        if (len(args)<3 or args[0][0]!='string' or args[1][1]!=',' or
                not re.fullmatch(r'"[+\-0-9A-Za-z]*"',args[0][1])):
            continue
        radix_text=''.join(part[1] for part in args[2:])
        if not re.fullmatch(r'[+-]?[0-9]{1,3}',radix_text):
            continue
        radix=int(radix_text)
        digits=args[0][1][1:-1]
        magnitude=digits[1:] if digits.startswith(('+','-')) else digits
        alphabet='0123456789abcdefghijklmnopqrstuvwxyz'
        valid=(2<=radix<=36 and bool(magnitude) and
               all(char.lower() in alphabet[:radix] for char in magnitude))
        if not valid:
            errors.append(LiteralArgumentError(source,position,
                f'BigInt({args[0][1]}, {radix_text}) is not a valid integer literal. '
                'The second argument is radix (2..36), not signal width; every digit must fit that radix. '
                'Use radix 16 for hexadecimal text, 2 for binary, or 10 for decimal. '
                'Ltl.is infers the signal width. Correct the representation while preserving the intended '
                'numeric value, all goal labels and output checks; the framework has not changed the value.'))
    if errors:
        errors[0].diagnostics=[error.diagnostic for error in errors]
        raise errors[0]


def unwrap(source):
    """Accept one whole-response fence, preserving its exact inner substring."""
    if not isinstance(source, str) or not source.strip() or len(source) > 100000:
        raise ValueError('return a nonempty LTL fragment of at most 100000 characters, or STOP')
    start, end, kind = 0, len(source), 'raw'
    # Fence lines must be standalone; do not search for a preferred code block.
    fences = list(re.finditer(r'(?m)^[ \t]*```[^\r\n]*(?:\r?\n|$)', source))
    if fences:
        opening, closing = fences[0], fences[-1]
        if (len(fences) != 2 or source[:opening.start()].strip() or source[closing.end():].strip()
                or opening.group().rstrip('\r\n') not in ('```', '```scala')
                or not opening.group().endswith('\n')
                or closing.group().rstrip('\r\n') != '```'):
            raise OutputFormatError('return raw LTL or exactly one complete scala code fence, with no surrounding prose or other blocks')
        start, end, kind = opening.end(), closing.start(), 'single-scala-fence'
    elif source.lstrip().startswith(('```', '{"', '{ "')):
        raise OutputFormatError('return raw LTL, not an incomplete fence or UT JSON')
    body = source[start:end]
    return body, {'policy': 'exact-ltl-envelope-v1', 'kind': kind,
        'raw_sha256': hashlib.sha256(source.encode()).hexdigest(),
        'ltl_sha256': hashlib.sha256(body.encode()).hexdigest(),
        'body_start_character': start, 'body_end_character': end,
        'body_first_line': source.count('\n', 0, start) + 1}


def normalize(source):
    """Canonicalize one unambiguous Scala operator spelling.

    Scala parses a curried symbolic call differently when whitespace replaces
    the receiver dot (``p ##(1)(q)``).  It is nevertheless an unambiguous LTL
    spelling: no operand, delay or goal changes.  Accept it at the LTL boundary
    and record the exact source edits instead of spending another model call on
    a mechanical compiler repair.  Raw provider output remains immutable in
    response.txt; generated model.ltl contains this canonical form.
    """
    body, audit = unwrap(source)
    stream = tokens(body)
    edits = []
    for index in range(1, len(stream) - 2):
        kind, value, position = stream[index]
        next_kind, next_value, next_position = stream[index + 1]
        if (kind, value, next_kind, next_value) != ('symbol','#','symbol','#'):
            continue
        if next_position != position + 1 or stream[index + 2][1] != '(':
            continue
        previous = stream[index - 1]
        if previous[1] == '.' or not (previous[0] in ('identifier','quoted') or
                                      previous[0] == 'symbol' and previous[1] in (')',']')):
            continue
        previous_end = previous[2] + len(previous[1])
        separator = body[previous_end:position]
        # Comments and other tokens are never rewritten.  A real whitespace
        # separator is required so canonical input remains byte-identical.
        if not separator or not separator.isspace():
            continue
        edits.append({'kind':'bounded_delay_receiver_dot','start_character':previous_end,
                      'end_character':position,'original':separator,'replacement':'.'})
    normalized = body
    for edit in reversed(edits):
        normalized = normalized[:edit['start_character']] + edit['replacement'] + normalized[edit['end_character']:]
    audit.update(canonicalization_policy='bounded-delay-receiver-dot-v1',
                 canonical_edits=edits,
                 normalized_ltl_sha256=hashlib.sha256(normalized.encode()).hexdigest())
    return normalized, audit


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
    source, _ = normalize(source)
    if source.strip() == 'STOP':
        return {'stop': True}
    stream = tokens(source)
    stack = []
    pairs = {}
    opens = {'(': ')', '[': ']', '{': '}'}
    for index, (kind, value, position) in enumerate(stream):
        io_field = index >= 2 and stream[index - 1][1] == '.' and stream[index - 2][1] == 'io'
        if kind == 'identifier' and value == 'def':
            raise ValueError(f'helper definitions are framework-owned (character {position}); call supplied Ltl helpers or compose expressions with local val')
        if (kind == 'symbol' and value == '=' and source[position:position + 2] == '=>'
                and (position == 0 or source[position - 1] not in '!#%&*+-/:<=>?@\\^|~')):
            raise ValueError(f'lambda helper definitions are framework-owned (character {position}); use supplied helpers or local val expressions')
        if ((kind in ('identifier', 'quoted') and value.strip('`') in FORBIDDEN and not io_field) or
                (kind == 'symbol' and value in (':=', '@'))):
            raise ValueError(f'{value} is not part of the LTL output contract (character {position}); the framework owns the UT and wiring')
        if kind == 'identifier' and value in ('val', 'var', 'def') and index + 1 < len(stream):
            if stream[index + 1][1].strip('`') in ('io', 'Gen', 'Ltl'):
                raise ValueError('do not redefine the supplied io, Gen or Ltl')
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
    check_bigint_literals(source,stream,pairs)
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
