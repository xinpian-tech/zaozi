// RVProbe eDSL — hazard hole closure for 21 RV32I instructions
//
// Fills the same 63 holes as handwrite.S:
//   21 instructions × {WAR, WAW, NoHazard}
//
// Each instruction = 3-line call site reusing a shared library function.
// The solver guarantees SAT = all hazard bins covered.

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
  def constraints() = rType(n, isAnd())

object Or extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isOr())

object Xor extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(n, isXor())

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
  def constraints() = iTypeAlu(n, isAndi())

object Ori extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = iTypeAlu(n, isOri())

object Xori extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = iTypeAlu(n, isXori())

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
// Shared library functions (CoverageLib.scala, ~90 lines total)
//
// def rType(n, opcode) =
//   (0 until n).foreach { i => instruction(i, opcode) { rdRange(1,32) & rs1Range(1,32) & rs2Range(1,32) } }
//   val seq = sequence(0, n)
//   seq.coverBins(_.rd, allRegs)
//   seq.coverBins(_.rs1, allRegs)
//   seq.coverBins(_.rs2, allRegs)
//   seq.coverRAW()
//   seq.coverWAR()       // ← one line: "there exists an adjacent pair with WAR"
//   seq.coverWAW()       // ← one line
//   seq.coverNoHazard()  // ← one line
//
// iTypeAlu, shiftImm, uType follow the same pattern with format-appropriate fields.
// coverWAR() automatically adapts: for U-type (no rs), it constrains
// the predecessor instruction's rs fields instead.
// ============================================================
//
// Total: 21 × 3 lines call site = 63 lines
//        + ~90 lines shared library (4 functions)
//        = ~153 lines
//
// Coverage guarantee: SAT = all 63 hazard bins closed, zero iteration.
// ============================================================
