// RVProbe eDSL — coverage hole closure for 21 RV32I instructions
//
// Fills 63 hazard holes: 21 instructions × {WAR, WAW, NoHazard}
// Fills 13 logical similarity holes: 6 logical instructions × {IDENTICAL, OPPOSITE, DIFFERENT}
//   (minus 5 already covered by random: and/or/xor/ori/andi SIMILAR, xori SIMILAR)
//
// Hazard: 3-line call site reusing shared library function (solver-guaranteed).
// Logical: 6 logical instructions upgraded from rType/iTypeAlu to rTypeLogical/iTypeLogical.

import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

val n = 35

// ============================================================
// R-type (10 instructions, shared library function: rType)
// ============================================================

object Add extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isAdd())

object Sub extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isSub())

object And extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rTypeLogical(n, isAnd(), and)

object Or extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rTypeLogical(n, isOr(), or)

object Xor extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rTypeLogical(n, isXor(), xor)

object Sll extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isSll())

object Srl extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isSrl())

object Sra extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isSra())

object Slt extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isSlt())

object Sltu extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isSltu())

// ============================================================
// I-type ALU (6 instructions, shared library function: iTypeAlu)
// ============================================================

object Addi extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = iTypeAlu(n, isAddi())

object Andi extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = iTypeLogical(n, isAndi(), andi)

object Ori extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = iTypeLogical(n, isOri(), ori)

object Xori extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = iTypeLogical(n, isXori(), xori)

object Slti extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = iTypeAlu(n, isSlti())

object Sltiu extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = iTypeAlu(n, isSltiu())

// ============================================================
// Shift-imm (3 instructions, shared library function: shiftImm)
// ============================================================

object Slli extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = shiftImm(n, isSlli())

object Srli extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = shiftImm(n, isSrli())

object Srai extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = shiftImm(n, isSrai())

// ============================================================
// U-type (2 instructions, shared library function: uType)
// ============================================================

object Lui extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = uType(n, isLui())

object Auipc extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = uType(n, isAuipc())

// ============================================================
// Shared library functions (CoverageLib.scala)
//
// def rType(n, opcode) =
//   (0 until n).foreach { i => instruction(i, opcode) { rdRange(1,32) & rs1Range(1,32) & rs2Range(1,32) } }
//   val seq = sequence(0, n)
//   seq.coverBins(_.rd, allRegs); seq.coverBins(_.rs1, allRegs); seq.coverBins(_.rs2, allRegs)
//   seq.coverRAW(); seq.coverWAR(); seq.coverWAW(); seq.coverNoHazard()
//
// def rTypeLogical(n, opcode, asmOp) =
//   rType(n, opcode)                        // all hazard + register bins
//   val r = freshReg(); li(r, 42)
//   asmOp(freshReg(), r, r)                 // IDENTICAL: same register → same value
//   val a = freshReg(); val b = freshReg()
//   li(a, 0x55555555); li(b, 0xAAAAAAAA)
//   asmOp(freshReg(), a, b)                 // OPPOSITE: all 32 bits differ
//   val c = freshReg(); val d = freshReg()
//   li(c, 0); li(d, 0xFF)
//   asmOp(freshReg(), c, d)                 // DIFFERENT: ≥5 bits differ
//
// def iTypeLogical(n, opcode, asmOp) =
//   iTypeAlu(n, opcode)                     // all hazard + register + imm bins
//   val r = freshReg(); li(r, 42)
//   asmOp(freshReg(), r, 42)                // IDENTICAL: rs1_value == imm
//   val r2 = freshReg(); li(r2, 0x555)
//   asmOp(freshReg(), r2, -1366)            // OPPOSITE
//   val r3 = freshReg(); li(r3, 0)
//   asmOp(freshReg(), r3, 0xFF)             // DIFFERENT
//
// iTypeAlu, shiftImm, uType follow the same pattern with format-appropriate fields.
// coverWAR() automatically adapts: for U-type (no rs), it constrains
// the predecessor instruction's rs fields instead.
// ============================================================
//
// Total call sites: 21 × 3 lines = 63 lines
// Library: ~150 lines (6 functions: rType, rTypeLogical, iTypeAlu, iTypeLogical, shiftImm, uType)
//
// Coverage guarantee:
//   - Hazard: SAT = all 63 bins closed (solver-guaranteed)
//   - Logical: setup li + op covers IDENTICAL/OPPOSITE/DIFFERENT (13 holes)
// ============================================================
