"""Lower sampled history in the auxiliary model only; native SVA is untouched."""
import re


def lower_past(code, clocks, default_clock='clock'):
    if code.count('endmodule') != 1:
        raise ValueError('past lowering requires one wrapper module')
    if re.search(r'\brvp_past_history_\w*', code):
        raise ValueError('reserved past history name collision')
    records = []
    # Innermost calls first so nested history keeps its sampling dependency.
    while True:
        matches = list(re.finditer(r'\$past\s*\(', code))
        if not matches:
            break
        call = matches[-1]
        stack = ['(']
        start = call.end()
        args = []
        for end in range(start, len(code)):
            char = code[end]
            if char in '([{':
                stack.append(char)
            elif char in ')]}':
                if not stack or stack.pop() != {')':'(', ']':'[', '}':'{'}[char]:
                    raise ValueError('unbalanced past arguments')
                if not stack:
                    args.append(code[start:end].strip())
                    break
            elif char == ',' and len(stack) == 1:
                args.append(code[start:end].strip())
                start = end + 1
        else:
            raise ValueError('unterminated past call')
        if not 1 <= len(args) <= 4 or not args[0]:
            raise ValueError('unsupported past arguments')
        ticks = args[1] if len(args) >= 2 else '1'
        if not re.fullmatch(r'\d+', ticks) or not 0 <= int(ticks) <= 4096:
            raise ValueError('past depth must be a bounded constant integer')
        if len(args) >= 3 and args[2]:
            raise ValueError('gated past is not supported by auxiliary lowering')
        clock = default_clock
        if len(args) == 4:
            event = re.fullmatch(r'@\(\s*posedge\s+(\w+)\s*\)', args[3])
            if event is None:
                raise ValueError('unsupported past sampling clock')
            clock = event[1]
        if clock not in clocks:
            raise ValueError('past clock is not a declared formal clock')
        expression = args[0]
        declarations = []
        names = [f'rvp_past_history_{len(records)}_{i}' for i in range(int(ticks))]
        previous = expression
        for name in names:
            # No reset or zero initialization: insufficient history is unknown.
            declarations.append(f'reg [$bits({expression})-1:0] {name};\n'
                                f'always @(posedge {clock}) {name} <= {previous};')
            previous = name
        records.append(dict(expression=expression, ticks=int(ticks), clock=clock,
                            registers=names, initialization='unknown'))
        statement = code.rfind(';', 0, call.start()) + 1
        prefix = code[statement:call.start()]
        if not re.match(r'\s*(?:wire\b[^;]*=|assign\b[^;]*=)', prefix, re.S):
            raise ValueError('auxiliary past must occur in a wrapper wire/continuous assignment')
        # Declare after the expression's existing wire dependencies, but before
        # the consuming statement. Hoisting $bits above DUT output wires breaks
        # Yosys width inference; appending regs after uses breaks VCS resolution.
        code = (code[:statement] + '\n' + '\n'.join(declarations) + '\n' +
                code[statement:call.start()] + '(' + previous + ')' + code[end+1:])
    if '$past' in code:
        raise ValueError('unrecognized past expression')
    return code, records
