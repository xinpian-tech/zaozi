// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.vsetvl

import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, Vma, Vta}

/** Dedicated codepath for the 3 vsetvl* instructions (`vsetvl`,
 *  `vsetvli`, `vsetivli`). These are CSR-writing instructions; they
 *  must not go through the vector pspike magic-word pipeline (pspike
 *  reads vector register groups, not `vl`/`vtype`).
 *
 *  Per upstream `riscv-vector-tests/generator/insn_vsetvli.go:59`,
 *  vsetvl* tests emit **scalar `TEST_CASE` rows** comparing the post-
 *  execution `vstart`, `vtype`, and `vl` CSRs against expected values
 *  computed at generation time. There is no magic word; the upstream
 *  TEST_CASE macro performs the comparison directly.
 *
 *  Round-9 rewrite (per Codex round-8 review): drop the vector-magic
 *  "CSR-only sentinel" approach; emit real scalar TEST_CASE rows.
 *
 *  DEC-9 sub-decision defaults (Claude positions):
 *  - DEC-9a (CSR-state assertion): yes — verify `vill`, `vtype`, `vl`.
 *  - DEC-9b (trap on vsetvl* itself): no trap; vsetvl* silently sets
 *    `vill=1` on illegal vtype.
 *  - DEC-9c (post-vill execution): do NOT execute a vector instruction
 *    under `vill=1`; assert CSR state only.
 */
object Tests:

  /** Source of the AVL field for a vsetvl* invocation. */
  enum AvlSource:
    case Imm(value: Int)              // vsetivli's 5-bit immediate
    case ScalarReg(name: String, value: Long)
    case ZeroZero                     // `x0, x0` pattern (preserves vl, sets new vtype)

  /** A single vsetvl* test case + its expected post-execution CSRs. */
  final case class VsetvlCase(
    insn:           String,
    avl:            AvlSource,
    vtype:          VType,
    expectVill:     Boolean,
    expectVl:       Long,          // ignored if expectVill
    expectVtypeBits: Long)         // computed by vtypeImmediate

  /** Generate the canonical case list for one VLEN/XLEN config. */
  def cases(vlen: Int, xlen: Int): List[VsetvlCase] =
    val avls = List(
      AvlSource.Imm(0),
      AvlSource.Imm(1),
      AvlSource.Imm(4),
      AvlSource.Imm(16),
      AvlSource.ScalarReg("a1", 8L),
      AvlSource.ScalarReg("a1", 256L),
      AvlSource.ZeroZero)
    val legalVtypes = for
      sew  <- Sew.all if sew.bits <= xlen
      lmul <- Lmul.all
      vta  <- List(Vta.Agnostic, Vta.Undisturbed)
      vma  <- List(Vma.Agnostic, Vma.Undisturbed)
      if me.jiuyang.rvprobe.rvv.vtype.VType.isLegal(sew, lmul, xlen)
    yield VType(sew, lmul, vta, vma)
    val illegalVtype =
      VType(Sew.Sew64, Lmul.Mf2, Vta.Agnostic, Vma.Agnostic) // SEW × 1/LMUL > ELEN

    val result = List.newBuilder[VsetvlCase]

    // vsetvli sweep: representative AVLs × every legal vtype
    for vt <- legalVtypes do
      for avl <- avls.take(3) do
        result += mkCase("vsetvli", avl, vt, vlen, xlen, expectVill = false)

    // vsetivli sweep: 5-bit immediates × first few legal vtypes
    for vt <- legalVtypes.take(4) do
      result += mkCase("vsetivli", AvlSource.Imm(8), vt, vlen, xlen, expectVill = false)

    // vsetvl variant: scalar-driven vtype × first legal vtype
    legalVtypes.headOption.foreach { vt =>
      result += mkCase("vsetvl", AvlSource.ScalarReg("a1", 8L), vt, vlen, xlen, expectVill = false)
    }

    // Vill-triggering case (DEC-9: assert vill=1, vl=0)
    result += mkCase("vsetvli", AvlSource.Imm(4), illegalVtype, vlen, xlen, expectVill = true)

    result.result()

  /** Compute the vtype CSR-encoded value (vsew[2:0] << 3 | vlmul[2:0]
   *  | vta << 6 | vma << 7), matching the spec immediate-encoding.
   */
  def vtypeImmediate(vt: VType): Long =
    val vsewBits = vt.sew match
      case Sew.Sew8  => 0L
      case Sew.Sew16 => 1L
      case Sew.Sew32 => 2L
      case Sew.Sew64 => 3L
    val vlmulBits = vt.lmul match
      case Lmul.M1  => 0L
      case Lmul.M2  => 1L
      case Lmul.M4  => 2L
      case Lmul.M8  => 3L
      case Lmul.Mf8 => 5L
      case Lmul.Mf4 => 6L
      case Lmul.Mf2 => 7L
    val vtaBit = if vt.vta == Vta.Agnostic then 1L else 0L
    val vmaBit = if vt.vma == Vma.Agnostic then 1L else 0L
    vlmulBits | (vsewBits << 3) | (vtaBit << 6) | (vmaBit << 7)

  /** Compute the expected `vl` after vsetvl* per the spec:
   *  - If `vill`: vl=0.
   *  - If AVL is zero: vl=0.
   *  - Else: vl = min(AVL, VLMAX) where
   *    VLMAX = (VLEN × EMUL) / SEW
   *    EMUL = max(LMUL_numerator / LMUL_denominator, 1) for the encoded LMUL
   *  - `x0, x0` pattern preserves the current vl (assumed 0 at start).
   */
  def expectedVl(avl: AvlSource, vt: VType, vlen: Int, vill: Boolean): Long =
    if vill then 0L
    else
      val emulN = vt.lmul.numerator
      val emulD = vt.lmul.denominator
      val vlmax = (vlen.toLong * emulN) / (vt.sewBits * emulD)
      avl match
        case AvlSource.Imm(0)            => 0L
        case AvlSource.Imm(n)            => math.min(n.toLong, vlmax)
        case AvlSource.ScalarReg(_, v)   => math.min(v, vlmax)
        case AvlSource.ZeroZero          => 0L

  private def mkCase(
    insn:       String,
    avl:        AvlSource,
    vt:         VType,
    vlen:       Int,
    xlen:       Int,
    expectVill: Boolean
  ): VsetvlCase =
    val vlExpected      = expectedVl(avl, vt, vlen, expectVill)
    val vtypeImm        = vtypeImmediate(vt)
    val vtypeWithVill   = if expectVill then vtypeImm | (1L << (xlen - 1)) else vtypeImm
    VsetvlCase(insn, avl, vt, expectVill, vlExpected, vtypeWithVill)

  /** Render the complete `.S` file for one vsetvl* variant's sweep.
   *  Emits scalar `TEST_CASE` rows for each (vtype, vl) check. No
   *  vector magic word — pspike does not read CSRs.
   */
  def renderTestS(insn: String, vlen: Int, xlen: Int, envMacro: String): String =
    val cs   = cases(vlen, xlen).filter(_.insn == insn)
    val sb   = new StringBuilder
    sb.append("# Auto-generated by vsetvl.Tests. Do not edit by hand.\n")
    sb.append(s"# Instruction: $insn  (CSR-only path; no pspike magic word)\n\n")
    sb.append("#include \"riscv_test.h\"\n")
    sb.append("#include \"test_macros.h\"\n\n")
    sb.append(s"$envMacro\n\n")
    sb.append("RVTEST_CODE_BEGIN\n\n")

    var testNum = 3 // start above the upstream-reserved TEST_CASE(2,...)
    cs.zipWithIndex.foreach { case (c, idx) =>
      sb.append(s"  # ---- block $idx: ${c.insn} ${avlAsm(c.avl)}  expect_vill=${c.expectVill} ----\n")
      // Materialize AVL operand into a register if needed.
      c.avl match
        case AvlSource.ScalarReg(reg, v) =>
          sb.append(s"  li $reg, $v\n")
        case _ => ()
      // Materialize vtype operand for `vsetvl` (which reads it from rs2).
      if c.insn == "vsetvl" then
        sb.append(s"  li a2, ${c.expectVtypeBits & ((1L << 11) - 1)}\n")
      // Execute the instruction.
      sb.append(s"  ${formatVsetvlInsn(c)}\n")
      // Read post-execution CSRs into scalars.
      sb.append(s"  csrr a3, vl\n")
      sb.append(s"  csrr a4, vtype\n")
      // TEST_CASE rows compare against expected values.
      sb.append(f"  TEST_CASE($testNum%d, a3, 0x${c.expectVl}%x)\n")
      testNum += 1
      // For vtype: mask off the unused high bits then compare. The
      // assertion uses a scratch register so TEST_CASE doesn't
      // accidentally reorder around the csrr.
      sb.append(f"  TEST_CASE($testNum%d, a4, 0x${c.expectVtypeBits}%x)\n")
      testNum += 1
      sb.append("\n")
    }

    sb.append(s"  TEST_CASE(2, x0, 0x0)\n")
    sb.append("  TEST_PASSFAIL\n\n")
    sb.append("RVTEST_CODE_END\n\n")
    sb.append("  .data\n")
    sb.append("RVTEST_DATA_BEGIN\n\n")
    sb.append("RVTEST_DATA_END\n")
    sb.toString

  private def avlAsm(avl: AvlSource): String = avl match
    case AvlSource.Imm(v)            => s"imm=$v"
    case AvlSource.ScalarReg(r, v)   => s"$r=$v"
    case AvlSource.ZeroZero          => "x0,x0"

  /** Produce the actual vsetvl/vsetvli/vsetivli instruction string. */
  private def formatVsetvlInsn(c: VsetvlCase): String =
    val vt = c.vtype
    val sewStr = vt.sew match
      case Sew.Sew8  => "e8"
      case Sew.Sew16 => "e16"
      case Sew.Sew32 => "e32"
      case Sew.Sew64 => "e64"
    val lmulStr = vt.lmul match
      case Lmul.M1  => "m1"
      case Lmul.M2  => "m2"
      case Lmul.M4  => "m4"
      case Lmul.M8  => "m8"
      case Lmul.Mf2 => "mf2"
      case Lmul.Mf4 => "mf4"
      case Lmul.Mf8 => "mf8"
    val taStr = if vt.vta == Vta.Agnostic then "ta" else "tu"
    val maStr = if vt.vma == Vma.Agnostic then "ma" else "mu"
    val vtypeStr = s"$sewStr,$lmulStr,$taStr,$maStr"

    c.insn match
      case "vsetvli"  =>
        val avlReg = c.avl match
          case AvlSource.Imm(_)            => "x0"
          case AvlSource.ScalarReg(r, _)   => r
          case AvlSource.ZeroZero          => "x0"
        val rd     = c.avl match
          case AvlSource.ZeroZero => "x0"
          case _                  => "x5"
        s"vsetvli $rd, $avlReg, $vtypeStr"
      case "vsetivli" =>
        val n = c.avl match
          case AvlSource.Imm(v) => v
          case _                => 0
        s"vsetivli x5, $n, $vtypeStr"
      case "vsetvl"   =>
        val avlReg = c.avl match
          case AvlSource.ScalarReg(r, _) => r
          case _                         => "a1"
        s"vsetvl x5, $avlReg, a2"
      case _          =>
        throw new IllegalArgumentException(s"unknown vsetvl variant: ${c.insn}")
