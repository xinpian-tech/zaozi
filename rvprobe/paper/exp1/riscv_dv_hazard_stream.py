"""
riscv-dv directed instruction stream for GPR hazard coverage.

Fills the 63 hazard holes (21 instructions × {WAR, WAW, NoHazard})
left by riscv-dv's random generation (which only covers RAW).

Usage:
  1. Copy this file to riscv-dv/pygen/pygen_src/
  2. Register in riscv_utils.py factory dict:
       "riscv_gpr_hazard_instr_stream": riscv_gpr_hazard_instr_stream,
  3. Add to test via add_directed_instr_stream("riscv_gpr_hazard_instr_stream", 10)
  4. Run: python3 run.py --test=riscv_rand_instr_test --simulator=pyflow --steps gen
"""

import copy
import logging
from pygen_src.riscv_directed_instr_lib import riscv_directed_instr_stream
from pygen_src.isa.riscv_instr import riscv_instr
from pygen_src.riscv_instr_gen_config import cfg
from pygen_src.riscv_instr_pkg import (
    riscv_instr_name_t,
    riscv_instr_format_t,
    riscv_reg_t,
)

# The 21 instructions missing WAR/WAW/NoHazard in riscv-dv coverage report.
# Grouped by format for register field awareness.
RTYPE_INSTRS = [
    riscv_instr_name_t.ADD,
    riscv_instr_name_t.SUB,
    riscv_instr_name_t.AND,
    riscv_instr_name_t.OR,
    riscv_instr_name_t.XOR,
    riscv_instr_name_t.SLL,
    riscv_instr_name_t.SRL,
    riscv_instr_name_t.SRA,
    riscv_instr_name_t.SLT,
    riscv_instr_name_t.SLTU,
]

ITYPE_INSTRS = [
    riscv_instr_name_t.ADDI,
    riscv_instr_name_t.ANDI,
    riscv_instr_name_t.ORI,
    riscv_instr_name_t.XORI,
    riscv_instr_name_t.SLTI,
    riscv_instr_name_t.SLTIU,
]

SHIFTIMM_INSTRS = [
    riscv_instr_name_t.SLLI,
    riscv_instr_name_t.SRLI,
    riscv_instr_name_t.SRAI,
]

UTYPE_INSTRS = [
    riscv_instr_name_t.LUI,
    riscv_instr_name_t.AUIPC,
]


def _make_instr(name, rd=None, rs1=None, rs2=None, imm=None):
    """Create a riscv_instr with specific register/immediate assignments."""
    instr = riscv_instr.get_instr(name.name)
    instr.set_rand_mode()
    if rd is not None:
        instr.rd = rd
    if rs1 is not None:
        instr.rs1 = rs1
    if rs2 is not None:
        instr.rs2 = rs2
    if imm is not None:
        instr.imm_str = str(imm)
        instr.imm = int(imm)
    instr.has_label = 0
    instr.atomic = 1
    return instr


def _rtype_hazard_pairs(name):
    """Generate 6 instructions covering WAR, WAW, NoHazard for an R-type instruction.

    R-type: rd, rs1, rs2.
    Hazard priority: RAW > WAR > WAW > NoHazard.

    For each pair, we must satisfy the target hazard AND exclude higher-priority ones.
    This is the same constraint satisfaction problem the engineer must solve mentally
    when hand-writing assembly — here encoded explicitly in Python.
    """
    instrs = []
    r = riscv_reg_t

    # WAR pair: curr.rd ∈ {prev.rs1, prev.rs2} ∧ prev.rd ∉ {curr.rs1, curr.rs2}
    instrs.append(_make_instr(name, rd=r.T0, rs1=r.T3, rs2=r.T4))
    instrs.append(_make_instr(name, rd=r.T3, rs1=r.T5, rs2=r.T6))
    # WAR: T3 == prev.rs1=T3 ✓; prev.rd=T0 ∉ {T5,T6} → ¬RAW ✓

    # WAW pair: prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR
    instrs.append(_make_instr(name, rd=r.S0, rs1=r.S1, rs2=r.S2))
    instrs.append(_make_instr(name, rd=r.S0, rs1=r.S3, rs2=r.S4))
    # WAW: S0 == prev.rd=S0 ✓; S0 ∉ {S3,S4} → ¬RAW ✓; S0 ∉ {S1,S2} → ¬WAR ✓

    # NoHazard pair: no register overlap
    instrs.append(_make_instr(name, rd=r.A0, rs1=r.A1, rs2=r.A2))
    instrs.append(_make_instr(name, rd=r.A3, rs1=r.A4, rs2=r.A5))

    return instrs


def _itype_hazard_pairs(name):
    """Generate 6 instructions covering WAR, WAW, NoHazard for an I-type ALU instruction.

    I-type ALU: rd, rs1, imm12. No rs2 — WAR only checks prev.rs1.
    """
    instrs = []
    r = riscv_reg_t

    # WAR: curr.rd == prev.rs1 ∧ prev.rd ≠ curr.rs1
    instrs.append(_make_instr(name, rd=r.T0, rs1=r.T3, imm=10))
    instrs.append(_make_instr(name, rd=r.T3, rs1=r.T5, imm=20))

    # WAW: prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR
    instrs.append(_make_instr(name, rd=r.S0, rs1=r.S1, imm=30))
    instrs.append(_make_instr(name, rd=r.S0, rs1=r.S3, imm=40))

    # NoHazard
    instrs.append(_make_instr(name, rd=r.A0, rs1=r.A1, imm=50))
    instrs.append(_make_instr(name, rd=r.A3, rs1=r.A4, imm=60))

    return instrs


def _shiftimm_hazard_pairs(name):
    """Generate 6 instructions covering WAR, WAW, NoHazard for a shift-immediate instruction.

    Same register fields as I-type (rd, rs1), but imm is a shift amount.
    """
    instrs = []
    r = riscv_reg_t

    # WAR
    instrs.append(_make_instr(name, rd=r.T0, rs1=r.T3, imm=1))
    instrs.append(_make_instr(name, rd=r.T3, rs1=r.T5, imm=2))

    # WAW
    instrs.append(_make_instr(name, rd=r.S0, rs1=r.S1, imm=3))
    instrs.append(_make_instr(name, rd=r.S0, rs1=r.S3, imm=4))

    # NoHazard
    instrs.append(_make_instr(name, rd=r.A0, rs1=r.A1, imm=5))
    instrs.append(_make_instr(name, rd=r.A3, rs1=r.A4, imm=6))

    return instrs


def _utype_hazard_pairs(name):
    """Generate 6+1 instructions covering WAR, WAW, NoHazard for a U-type instruction.

    U-type: rd, imm20. NO rs1, NO rs2.

    Key challenge: two U-type instructions cannot produce WAR (no rs fields).
    WAR requires a non-U-type predecessor with rs fields.
    We use ADDI as the helper instruction.

    This cross-format dependency is the kind of reasoning that RVProbe's
    coverWAR() handles automatically but must be done manually here.
    """
    instrs = []
    r = riscv_reg_t

    # WAR: need ADDI predecessor with rs1 matching the U-type's rd
    instrs.append(_make_instr(riscv_instr_name_t.ADDI, rd=r.T0, rs1=r.T3, imm=0))
    instrs.append(_make_instr(name, rd=r.T3, imm=0x12345))
    # WAR: T3 == prev.rs1=T3 ✓; U-type has no rs → ¬RAW ✓

    # WAW: two U-type with same rd
    instrs.append(_make_instr(name, rd=r.S0, imm=0xAAAAA))
    instrs.append(_make_instr(name, rd=r.S0, imm=0xBBBBB))

    # NoHazard: different rd
    instrs.append(_make_instr(name, rd=r.A0, imm=0x11111))
    instrs.append(_make_instr(name, rd=r.A3, imm=0x22222))

    return instrs


class riscv_gpr_hazard_instr_stream(riscv_directed_instr_stream):
    """Directed instruction stream that covers WAR, WAW, and NoHazard for all
    21 RV32I instructions missing these hazard bins after riscv-dv saturation.

    This stream generates 128 instructions (21 instructions × 6 lines each,
    plus 2 ADDI helpers for U-type WAR coverage).

    The implementation explicitly constructs each hazard pair — the same
    constraint satisfaction problem that hand-written assembly requires,
    but encoded in Python instead of raw .S.

    Compare with RVProbe, where the equivalent is:
        seq.coverWAR()
        seq.coverWAW()
        seq.coverNoHazard()
    (3 lines, format-agnostic, solver-guaranteed correct)
    """

    def __init__(self):
        super().__init__()
        self.name = "riscv_gpr_hazard_instr_stream"

    def post_randomize(self):
        self.instr_list = []

        # R-type: 10 instructions × 6 = 60
        for name in RTYPE_INSTRS:
            self.instr_list.extend(_rtype_hazard_pairs(name))

        # I-type ALU: 6 instructions × 6 = 36
        for name in ITYPE_INSTRS:
            self.instr_list.extend(_itype_hazard_pairs(name))

        # Shift-imm: 3 instructions × 6 = 18
        for name in SHIFTIMM_INSTRS:
            self.instr_list.extend(_shiftimm_hazard_pairs(name))

        # U-type: 2 instructions × (6+1) = 14
        for name in UTYPE_INSTRS:
            self.instr_list.extend(_utype_hazard_pairs(name))

        # Label first and last instructions
        self.instr_list[0].comment = "Start %s" % self.name
        self.instr_list[-1].comment = "End %s" % self.name

        logging.info("Generated %d instructions for GPR hazard coverage",
                     len(self.instr_list))
