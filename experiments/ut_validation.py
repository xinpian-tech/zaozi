"""Fail-closed structural checks for the firtool-emitted, single-DUT verification wrapper.

Only direct/alias wiring is accepted (plus reset inversion). This is deliberately
stricter than arbitrary semantic equivalence and is not a general SV parser.
"""
import re


def write_targets(code):
    """Find statement assignments, not comparisons inside Gen properties or RHS expressions."""
    code = re.sub(r"\b\w+\s*:\s*assert\s+property\b[^;]*;", "", code)
    targets = []
    for statement in code.split(";"):
        depth = 0
        for index, ch in enumerate(statement):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            if depth or ch != "=" or statement[index:index + 2] == "==":
                continue
            previous = statement[index - 1:index]
            if previous in ("=", "!", ">"):
                continue
            lhs = statement[:index - 1] if previous == "<" else statement[:index]
            match = re.search(r"\b(\w+)(?:\s*\[[^]]+\])*\s*$", lhs)
            if not match:
                raise ValueError("unsupported assignment target in UT")
            targets.append(match[1])
            break
    return targets


def validate(text, design, top, labels):
    code = re.sub(r"/\*.*?\*/|//[^\n]*", " ", text, flags=re.S)
    if re.search(r"\b(assume|restrict|force|release|bind|alias|tran|import|export|defparam|generate|endgenerate|primitive|initial|specify|interface|function|task)\b|`", code):
        raise ValueError("UT contains forbidden restrictions, external binding or host interfaces")
    allowed_system = {"past", "stable", "rose", "fell", "signed", "unsigned", "bits", "clog2"}
    if set(re.findall(r"\$(\w+)", code)) - allowed_system:
        raise ValueError("UT contains unsupported SystemVerilog system calls")
    modules = re.findall(r"\bmodule\s+(\w+)\s*\(", code)
    if modules != [top] or code.count("endmodule") != 1:
        raise ValueError("UT must contain exactly its wrapper module, never a replacement DUT")
    header = re.search(r"\bmodule\s+" + re.escape(top) + r"\s*\((.*?)\);", code, re.S)
    actual, direction, width = {}, None, 1
    for field in header[1].split(","):
        m = re.fullmatch(r"\s*(?:(input|output)\s+)?(?:\[(\d+):0\]\s*)?(\w+)\s*", field)
        if not m:
            raise ValueError("unsupported UT port declaration")
        if m[1]:
            direction, width = m[1], int(m[2]) + 1 if m[2] else 1
        elif m[2]:
            width = int(m[2]) + 1
        if m[3] in actual:
            raise ValueError("duplicate UT port")
        actual[m[3]] = (direction, width)
    expected = {p.name: (p.direction, p.width) for p in design.data_ports}
    expected.update(clock=("input", 1), reset=("input", 1))
    if actual != expected:
        raise ValueError("UT boundary ports differ from the fixed design binding")
    # FIRRTL emits each external instance in named-port form; reject helpers/new instances.
    instances = list(re.finditer(r"\b(\w+)\s+(\w+)\s*\(([^;]*?)\)\s*;", code, re.S))
    instances = [m for m in instances if m[1] not in {"module", "assert"}]
    if len(instances) != 1 or instances[0][1] != design.top:
        raise ValueError("UT must instantiate exactly one original DUT")
    pins = {}
    for field in instances[0][3].split(","):
        m = re.fullmatch(r"\s*\.(\w+)\s*\((.*?)\)\s*", field, re.S)
        if not m or m[1] in pins:
            raise ValueError("invalid or duplicate DUT pin connection")
        pins[m[1]] = m[2].strip()
    if set(pins) != {p.name for p in design.ports}:
        raise ValueError("DUT pins do not match the manifest")
    assignments = {}
    for m in re.finditer(r"\bassign\s+(\w+)\s*=\s*([^;]+);", code):
        if m[1] in assignments:
            raise ValueError("multiply driven UT wire")
        assignments[m[1]] = m[2].strip()
    writes = write_targets(code)

    def resolve(expr, seen=()):
        expr = re.sub(r"\s+", "", expr)
        while expr.startswith("(") and expr.endswith(")"):
            expr = expr[1:-1]
        if expr.startswith(("~", "!")):
            return "!" + resolve(expr[1:], seen)
        if not re.fullmatch(r"\w+", expr) or expr in seen:
            raise ValueError("DUT wiring must be direct aliases, not constants, logic or cycles")
        if expr in assignments:
            if writes.count(expr) != 1:
                raise ValueError("DUT wiring has additional procedural drivers")
            return resolve(assignments[expr], (*seen, expr))
        if expr in writes:
            raise ValueError("DUT boundary has a procedural driver")
        return expr

    driven_outputs = set()
    inputs = {name for name, (direction, _) in expected.items() if direction == "input"}
    for p in design.ports:
        if p.direction == "input":
            want = p.name
            if p.name == design.clock:
                want = "clock"
            elif p.name == design.reset:
                want = "!reset" if design.reset_active_low else "reset"
            if resolve(pins[p.name]) != want:
                raise ValueError(f"DUT input {p.name} is not faithfully connected")
        else:
            net = resolve(pins[p.name])
            if net in inputs or net in driven_outputs or not re.fullmatch(r"\w+", net) or resolve(p.name) != net:
                raise ValueError(f"DUT output {p.name} is not faithfully connected")
            driven_outputs.add(net)
    actual_labels = re.findall(r"\b(\w+)\s*:\s*assert\s+property\b", code)
    if len(actual_labels) != len(labels) or set(actual_labels) != set(labels):
        raise ValueError("compiled Gen labels differ from the response")
    if len(re.findall(r"\bassert\b", code)) != len(labels) or re.search(r"\bcover\b", code):
        raise ValueError("extra verification properties are not allowed")
    return {"policy": "faithful-single-dut-v1", "passed": True, "ports": len(expected),
            "dut_instances": 1, "assumptions": 0, "goals": len(labels)}
