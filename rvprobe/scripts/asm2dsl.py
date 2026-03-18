#!/usr/bin/env python3
"""
asm2dsl.py — Convert RISC-V assembly syntax to rvprobe DSL syntax.

Parses AsmApi.scala to extract all available instruction signatures,
then converts GAS-style assembly lines into DSL function calls.

Usage:
    python3 asm2dsl.py input.s                  # from file
    echo "addi x1, x1, 1" | python3 asm2dsl.py # from stdin
    python3 asm2dsl.py -a /path/to/AsmApi.scala input.s  # custom AsmApi path
"""

import re
import sys
import argparse
from pathlib import Path
from collections import defaultdict

# Default path to AsmApi.scala relative to this script
DEFAULT_ASM_API = Path(__file__).resolve().parent.parent / "src" / "AsmApi.scala"

# RISC-V ABI register aliases → canonical xN names
REGISTER_ALIASES = {
    "zero": "x0",
    "ra": "x1",
    "sp": "x2",
    "gp": "x3",
    "tp": "x4",
    "t0": "x5",
    "t1": "x6",
    "t2": "x7",
    "s0": "x8",
    "fp": "x8",
    "s1": "x9",
    "a0": "x10",
    "a1": "x11",
    "a2": "x12",
    "a3": "x13",
    "a4": "x14",
    "a5": "x15",
    "a6": "x16",
    "a7": "x17",
    "s2": "x18",
    "s3": "x19",
    "s4": "x20",
    "s5": "x21",
    "s6": "x22",
    "s7": "x23",
    "s8": "x24",
    "s9": "x25",
    "s10": "x26",
    "s11": "x27",
    "t3": "x28",
    "t4": "x29",
    "t5": "x30",
    "t6": "x31",
}

# All valid register names (x0-x31)
VALID_REGISTERS = {f"x{i}" for i in range(32)}

# Floating-point register aliases → canonical xN names
# In the DSL, FP registers share the x0-x31 namespace
FP_REGISTER_ALIASES = {f"f{i}": f"x{i}" for i in range(32)}
FP_REGISTER_ALIASES.update({
    "ft0": "x0", "ft1": "x1", "ft2": "x2", "ft3": "x3",
    "ft4": "x4", "ft5": "x5", "ft6": "x6", "ft7": "x7",
    "fs0": "x8", "fs1": "x9",
    "fa0": "x10", "fa1": "x11", "fa2": "x12", "fa3": "x13",
    "fa4": "x14", "fa5": "x15", "fa6": "x16", "fa7": "x17",
    "fs2": "x18", "fs3": "x19", "fs4": "x20", "fs5": "x21",
    "fs6": "x22", "fs7": "x23", "fs8": "x24", "fs9": "x25",
    "fs10": "x26", "fs11": "x27",
    "ft8": "x28", "ft9": "x29", "ft10": "x30", "ft11": "x31",
})

# RISC-V rounding mode names → numeric values
ROUNDING_MODES = {
    "rne": 0,  # Round to Nearest, ties to Even
    "rtz": 1,  # Round towards Zero
    "rdn": 2,  # Round Down (towards -inf)
    "rup": 3,  # Round Up (towards +inf)
    "rmm": 4,  # Round to Nearest, ties to Max Magnitude
    "dyn": 7,  # Dynamic (use frm register)
}

# RISC-V CSR name → number mapping (commonly used CSRs)
CSR_MAP = {
    # Machine-level CSRs
    "mstatus": 0x300, "misa": 0x301, "medeleg": 0x302, "mideleg": 0x303,
    "mie": 0x304, "mtvec": 0x305, "mcounteren": 0x306,
    "mscratch": 0x340, "mepc": 0x341, "mcause": 0x342, "mtval": 0x343,
    "mip": 0x344,
    "pmpcfg0": 0x3a0, "pmpcfg1": 0x3a1, "pmpcfg2": 0x3a2, "pmpcfg3": 0x3a3,
    "pmpaddr0": 0x3b0, "pmpaddr1": 0x3b1, "pmpaddr2": 0x3b2, "pmpaddr3": 0x3b3,
    "mvendorid": 0xf11, "marchid": 0xf12, "mimpid": 0xf13, "mhartid": 0xf14,
    # Supervisor-level CSRs
    "sstatus": 0x100, "sie": 0x104, "stvec": 0x105, "scounteren": 0x106,
    "sscratch": 0x140, "sepc": 0x141, "scause": 0x142, "stval": 0x143,
    "sip": 0x144, "satp": 0x180,
    # User-level CSRs
    "ustatus": 0x000, "fflags": 0x001, "frm": 0x002, "fcsr": 0x003,
    "cycle": 0xc00, "time": 0xc01, "instret": 0xc02,
}


def parse_asm_api(api_path: Path) -> dict[str, list[list[tuple[str, str]]]]:
    """
    Parse AsmApi.scala to extract function signatures.

    Returns: {func_name: [[(param_name, param_type), ...], ...]}
    Each function can have multiple overloads (e.g., beq with Int offset vs String target).
    """
    signatures = defaultdict(list)
    pattern = re.compile(
        r"^def\s+(\w+)\(([^)]*)\)\(using"
    )

    text = api_path.read_text()
    for line in text.splitlines():
        m = pattern.match(line)
        if not m:
            continue
        name = m.group(1)
        params_str = m.group(2).strip()

        if not params_str:
            # Zero-arg instruction like ecall(), nop()
            signatures[name].append([])
            continue

        params = []
        for param in params_str.split(","):
            param = param.strip()
            parts = param.split(":")
            if len(parts) == 2:
                pname = parts[0].strip()
                ptype = parts[1].strip()
                params.append((pname, ptype))
        signatures[name].append(params)

    return dict(signatures)


def gas_mnemonic_to_dsl_name(mnemonic: str) -> str:
    """
    Convert GAS-style mnemonic to DSL function name.

    GAS uses dots and lowercase: add.uw, amoswap.w, c.addi, lr.w
    DSL uses camelCase:          addUw,  amoswapW,  cAddi,  lrW

    Also handles special suffixes like .aq, .rl, .aqrl
    """
    # Handle dot-separated parts
    if "." not in mnemonic:
        return mnemonic

    parts = mnemonic.split(".")
    result = parts[0]
    for part in parts[1:]:
        if part:
            result += part[0].upper() + part[1:]
    return result


def normalize_register(operand: str) -> str | None:
    """If operand is a register name (xN, ABI alias, or FP register), return canonical xN. Else None."""
    op = operand.lower().strip()
    if op in VALID_REGISTERS:
        return op
    if op in REGISTER_ALIASES:
        return REGISTER_ALIASES[op]
    if op in FP_REGISTER_ALIASES:
        return FP_REGISTER_ALIASES[op]
    return None


def is_integer(s: str) -> bool:
    """Check if a string represents an integer (decimal or hex)."""
    s = s.strip()
    try:
        if s.startswith("0x") or s.startswith("0X") or s.startswith("-0x") or s.startswith("-0X"):
            int(s, 16)
        else:
            int(s)
        return True
    except ValueError:
        return False


def is_int_compatible(s: str) -> bool:
    """Check if operand can be used as Int: literal integer, CSR name, or rounding mode."""
    return is_integer(s) or s.strip().lower() in CSR_MAP or s.strip().lower() in ROUNDING_MODES


def parse_integer(s: str) -> int:
    """Parse integer from decimal or hex string."""
    s = s.strip()
    if s.startswith("0x") or s.startswith("0X"):
        return int(s, 16)
    if s.startswith("-0x") or s.startswith("-0X"):
        return -int(s[1:], 16)
    return int(s)


def resolve_int_operand(s: str) -> str:
    """Resolve an Int-typed operand: numeric literal, CSR name, or rounding mode."""
    s = s.strip()
    low = s.lower()
    if low in CSR_MAP:
        return str(CSR_MAP[low])
    if low in ROUNDING_MODES:
        return str(ROUNDING_MODES[low])
    return str(parse_integer(s))


def parse_load_store_operand(operands_str: str) -> list[str]:
    """
    Parse load/store operands with offset(base) syntax.
    e.g., "x1, 8(x2)" → ["x1", "x2", "8"]
          "x1, (x2)"   → ["x1", "x2", "0"]
    """
    # Match pattern: offset(reg) or (reg)
    m = re.search(r"(-?\w*)\((\w+)\)", operands_str)
    if not m:
        return None

    offset_str = m.group(1)
    base_reg = m.group(2)

    # Get the part before offset(reg)
    prefix = operands_str[:m.start()].rstrip(", ")
    parts = [p.strip() for p in prefix.split(",") if p.strip()] if prefix else []

    offset = offset_str if offset_str else "0"
    parts.append(base_reg)
    parts.append(offset)
    return parts


def select_overload(
    overloads: list[list[tuple[str, str]]], operands: list[str]
) -> list[tuple[str, str]] | None:
    """
    Select the best matching overload based on operand count and types.
    Prefers String-target overloads for label references in branches.
    """
    candidates = []
    for params in overloads:
        if len(params) != len(operands):
            continue
        # Check type compatibility
        compatible = True
        for (pname, ptype), operand in zip(params, operands):
            reg = normalize_register(operand)
            if ptype == "Register" and reg is None:
                compatible = False
                break
            if ptype == "Int" and not is_int_compatible(operand):
                # Could be a label → try String overload instead
                compatible = False
                break
            if ptype == "String" and (is_integer(operand) or is_int_compatible(operand)):
                # Numeric value shouldn't match String overload
                compatible = False
                break
        if compatible:
            candidates.append(params)

    if not candidates:
        return None
    # If multiple matches, prefer String overload for labels
    return candidates[0]


def format_operand(operand: str, param_type: str) -> str:
    """Format a single operand for DSL output."""
    if param_type == "Register":
        reg = normalize_register(operand)
        return reg if reg else operand
    elif param_type == "Int":
        return resolve_int_operand(operand)
    elif param_type == "String":
        return f'"{operand}"'
    return operand


def convert_directive(line: str) -> str | None:
    """Convert assembler directives to DSL calls."""
    line = line.strip()

    # .section name[,"flags"[,@type]]
    m = re.match(r'\.section\s+(\S+?)(?:\s*,\s*"([^"]*)"(?:\s*,\s*(@\w+))?)?$', line)
    if m:
        name = m.group(1).rstrip(",")
        flags = m.group(2)
        sectype = m.group(3)
        if flags and sectype:
            return f'section("{name}", "{flags}", "{sectype}")'
        if flags:
            return f'section("{name}", "{flags}")'
        return f'section("{name}")'

    # .global / .globl symbol
    m = re.match(r"\.(?:global|globl)\s+(\S+)", line)
    if m:
        return f'global("{m.group(1)}")'

    # .align N
    m = re.match(r"\.align\s+(\d+)", line)
    if m:
        return f"align({m.group(1)})"

    # .balign N
    m = re.match(r"\.balign\s+(\d+)", line)
    if m:
        return f"balign({m.group(1)})"

    # .word value
    m = re.match(r"\.word\s+(\S+)", line)
    if m:
        return f"word({m.group(1)})"

    # .dword value
    m = re.match(r"\.dword\s+(\S+)", line)
    if m:
        return f"dword({m.group(1)})"

    # .zero size
    m = re.match(r"\.zero\s+(\d+)", line)
    if m:
        return f"zero({m.group(1)})"

    return None


def convert_line(
    line: str,
    signatures: dict[str, list[list[tuple[str, str]]]],
) -> str:
    """Convert a single assembly line to DSL syntax."""
    original = line
    line = line.strip()

    # Empty line
    if not line:
        return ""

    # Comment-only line
    if line.startswith("#") or line.startswith("//"):
        return f"// {line.lstrip('#/ ')}"

    # Strip inline comment
    comment = ""
    for comment_char in ["#", "//"]:
        idx = line.find(comment_char)
        if idx >= 0:
            comment = f" // {line[idx:].lstrip('#/ ')}"
            line = line[:idx].strip()

    # Label definition: "name:" or "name: instruction"
    m = re.match(r"^(\w+):\s*(.*)", line)
    if m:
        label_name = m.group(1)
        rest = m.group(2).strip()
        result = f'label("{label_name}")'
        if rest:
            # Label followed by instruction on same line
            rest_converted = convert_line(rest, signatures)
            result += f"\n{rest_converted}"
        return result + comment

    # Assembler directives
    directive = convert_directive(line)
    if directive is not None:
        return directive + comment

    # Other directives we don't handle → raw()
    if line.startswith("."):
        return f'raw("{line}")' + comment

    # Instruction: mnemonic operand1, operand2, ...
    parts = line.split(None, 1)
    mnemonic = parts[0].strip()
    operands_str = parts[1].strip() if len(parts) > 1 else ""

    # Convert GAS mnemonic to DSL name
    dsl_name = gas_mnemonic_to_dsl_name(mnemonic)

    # Try load/store offset(base) syntax: lw x1, 8(x2) → lw(x1, x2, 8)
    operands = None
    is_mem_op = False
    if "(" in operands_str and ")" in operands_str:
        operands = parse_load_store_operand(operands_str)
        is_mem_op = operands is not None

    if operands is None:
        # Standard comma/space-separated operands
        if operands_str:
            operands = [op.strip() for op in operands_str.replace(",", " ").split() if op.strip()]
        else:
            operands = []

    # GAS CSR pseudo-instructions have swapped operand order vs DSL:
    #   GAS: csrw csr, rs1  →  DSL: csrw(rs1, csr)
    #   GAS: csrs csr, rs1  →  DSL: csrs(rs1, csr)
    #   GAS: csrr rd, csr   →  DSL: csrr(rd, csr)  (same order)
    if dsl_name in ("csrw", "csrs", "csrc") and len(operands) == 2:
        operands[0], operands[1] = operands[1], operands[0]

    # GAS zero-operand forms with implicit x0 registers:
    #   sfence.vma → sfenceVma(x0, x0)
    if dsl_name in ("sfenceVma",) and len(operands) == 0:
        operands = ["x0", "x0"]

    # GAS puts rounding mode last, DSL puts it second:
    #   GAS: fround.s rd, rs1, rm     → DSL: froundS(rd, rm, rs1)
    #   GAS: fsub.s   rd, rs1, rs2, rm → DSL: fsubS(rd, rm, rs1, rs2)
    if dsl_name in signatures and len(operands) >= 3:
        for overload in signatures[dsl_name]:
            if len(overload) == len(operands) and len(overload) >= 3:
                pnames = [p[0] for p in overload]
                ptypes = [p[1] for p in overload]
                # Detect: DSL has rm as 2nd param (Int) but GAS has it last
                if ptypes[1] == "Int" and pnames[1] == "rm":
                    last_op = operands[-1].strip().lower()
                    if last_op in ROUNDING_MODES:
                        # Move last operand (rm) to position 1
                        rm_op = operands.pop()
                        operands.insert(1, rm_op)
                        break

    # Look up in signatures
    if dsl_name not in signatures:
        # Try as-is (might be a pseudo-instruction)
        return f'pseudo("{mnemonic}", "{operands_str}")' + comment

    overloads = signatures[dsl_name]
    params = select_overload(overloads, operands)

    if params is None:
        # No matching overload found
        if not operands and any(len(p) == 0 for p in overloads):
            # Zero-arg match
            return f"{dsl_name}()" + comment
        # Fallback to pseudo
        return f'pseudo("{mnemonic}", "{operands_str}")' + comment

    if not params:
        return f"{dsl_name}()" + comment

    # For memory ops (offset(base) syntax), GAS order is: value, offset(base) → [value, base, offset]
    # DSL store signatures use (rs1=base, rs2=value, offset) while loads use (rd=dest, rs1=base, offset)
    # Detect stores: if first DSL param is "rs1" but first GAS operand is the value register
    if is_mem_op and len(params) >= 3:
        param_names = [p[0] for p in params]
        if param_names[0] == "rs1" and param_names[1] == "rs2":
            # Store: GAS gives [rs2, rs1, offset], DSL wants [rs1, rs2, offset]
            operands[0], operands[1] = operands[1], operands[0]

    # Format each operand
    formatted = []
    for (pname, ptype), operand in zip(params, operands):
        formatted.append(format_operand(operand, ptype))

    return f"{dsl_name}({', '.join(formatted)})" + comment


def convert(input_text: str, signatures: dict) -> str:
    """Convert assembly text to DSL code."""
    lines = input_text.splitlines()
    output_lines = []
    for line in lines:
        converted = convert_line(line, signatures)
        output_lines.append(converted)
    return "\n".join(output_lines)


def main():
    parser = argparse.ArgumentParser(
        description="Convert RISC-V assembly to rvprobe DSL syntax"
    )
    parser.add_argument(
        "input",
        nargs="?",
        type=argparse.FileType("r"),
        default=sys.stdin,
        help="Input assembly file (default: stdin)",
    )
    parser.add_argument(
        "-a", "--asm-api",
        type=Path,
        default=DEFAULT_ASM_API,
        help=f"Path to AsmApi.scala (default: {DEFAULT_ASM_API})",
    )
    parser.add_argument(
        "-o", "--output",
        type=argparse.FileType("w"),
        default=sys.stdout,
        help="Output file (default: stdout)",
    )

    args = parser.parse_args()

    if not args.asm_api.exists():
        print(f"Error: AsmApi.scala not found at {args.asm_api}", file=sys.stderr)
        print("Use -a flag to specify the path.", file=sys.stderr)
        sys.exit(1)

    signatures = parse_asm_api(args.asm_api)
    input_text = args.input.read()
    result = convert(input_text, signatures)
    args.output.write(result)
    if not result.endswith("\n"):
        args.output.write("\n")


if __name__ == "__main__":
    main()
