#!/usr/bin/env python3
"""
compare_objdump.py — Compare two objdump -d outputs by instruction encoding.

Parses objdump disassembly, extracts (hex_bytes, mnemonic, operands) per line,
and compares instruction encodings (hex bytes are authoritative).

Usage:
    python3 compare_objdump.py original.dump output.dump
"""

import re
import sys
import argparse


def parse_objdump(filepath: str) -> list[tuple[str, str, str]]:
    """
    Parse objdump -d output into list of (hex_bytes, mnemonic, operands).
    Skips non-instruction lines (headers, labels, blank lines).
    """
    # objdump instruction line pattern:
    #   80000000:  00000297    auipc   t0,0x0
    # Captures: hex bytes, mnemonic, operands (optional)
    pattern = re.compile(
        r"^\s*[0-9a-f]+:\s+"       # address:
        r"([0-9a-f ]+?)\s{2,}"    # hex bytes (separated by 2+ spaces from mnemonic)
        r"(\S+)"                    # mnemonic
        r"(?:\s+(.*))?$"           # operands (optional)
    )

    instructions = []
    with open(filepath) as f:
        for line in f:
            m = pattern.match(line)
            if not m:
                continue
            hex_bytes = m.group(1).strip()
            mnemonic = m.group(2).strip()
            operands = (m.group(3) or "").strip()
            instructions.append((hex_bytes, mnemonic, operands))

    return instructions


def normalize_hex(hex_str: str) -> str:
    """Normalize hex bytes by removing spaces and lowercasing."""
    return hex_str.replace(" ", "").lower()


def compare(original: list, output: list) -> tuple[bool, list[str]]:
    """
    Compare two instruction lists.
    Returns (passed, diff_messages).
    """
    diffs = []
    max_len = max(len(original), len(output))

    if len(original) != len(output):
        diffs.append(
            f"Instruction count mismatch: original={len(original)}, output={len(output)}"
        )

    for i in range(min(len(original), len(output))):
        orig_hex, orig_mn, orig_ops = original[i]
        out_hex, out_mn, out_ops = output[i]

        if normalize_hex(orig_hex) != normalize_hex(out_hex):
            diffs.append(
                f"  [{i:4d}] ENCODING MISMATCH:\n"
                f"         original: {orig_hex}  {orig_mn} {orig_ops}\n"
                f"         output:   {out_hex}  {out_mn} {out_ops}"
            )
        elif orig_mn != out_mn:
            # Same encoding but different mnemonic (e.g., alias vs canonical)
            # This is acceptable — just note it
            pass

    return (len(diffs) == 0, diffs)


def main():
    parser = argparse.ArgumentParser(
        description="Compare two objdump -d outputs by instruction encoding"
    )
    parser.add_argument("original", help="Original objdump output")
    parser.add_argument("output", help="Generated objdump output")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="Show all instructions, not just mismatches")
    args = parser.parse_args()

    original = parse_objdump(args.original)
    output = parse_objdump(args.output)

    if not original:
        print(f"ERROR: No instructions found in {args.original}", file=sys.stderr)
        sys.exit(2)
    if not output:
        print(f"ERROR: No instructions found in {args.output}", file=sys.stderr)
        sys.exit(2)

    passed, diffs = compare(original, output)

    if args.verbose:
        print(f"Original: {len(original)} instructions")
        print(f"Output:   {len(output)} instructions")

    if passed:
        print(f"PASS: {len(original)} instructions match")
        sys.exit(0)
    else:
        print(f"FAIL: {len(diffs)} difference(s) found:")
        for d in diffs:
            print(d)
        sys.exit(1)


if __name__ == "__main__":
    main()
