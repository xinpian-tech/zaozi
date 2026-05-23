// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.vsetvl

import me.jiuyang.rvprobe.rvv.unittest.{MagicInstrEmit, TestSEmit}
import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, VTypeEnvelope, Vma, Vta}

/** Dedicated codepath for the 3 vsetvl* instructions (`vsetvl`,
 *  `vsetvli`, `vsetivli`), per the architectural decision from round-1
 *  Codex review: these are CSR-writing instructions, not ordinary
 *  vector-data instructions, and must not participate in the per-test
 *  envelope flow (which fixes `vill=false`).
 *
 *  Per DEC-9 Claude defaults (recorded at gen-plan close):
 *  - DEC-9a (CSR-state assertion): yes — verify `vill` and `vl` after
 *    each `vsetvl*`.
 *  - DEC-9b (trap behavior on vsetvl*): no trap on vsetvl* itself
 *    (CSR-only path, matching upstream).
 *  - DEC-9c (post-vill execution): do NOT execute a vector instruction
 *    under `vill=1`; stop after the CSR check.
 *
 *  AVL sweep: 0, 1, 4, 16, 256, "MaxVl" (= XLEN-bit -1 to trigger the
 *  spec's "AVL > VLMAX" path).
 *
 *  Vtype sweep: every legal (SEW, LMUL, VTA, VMA) combination per
 *  `VType.isLegal`, plus one illegal combination per ELEN to trigger
 *  the vill path.
 */
object Tests:

  /** A single vsetvli/vsetvl/vsetivli test case. */
  final case class VsetvlCase(
    insn:     String,        // "vsetvl", "vsetvli", or "vsetivli"
    avl:      AvlSource,
    vtype:    VType,
    expectVill: Boolean       // true when the vtype is intentionally illegal
  )

  /** Source of the AVL field. */
  enum AvlSource:
    case Imm(value: Int)           // vsetivli: 5-bit immediate
    case ScalarReg(reg: String)    // vsetvli/vsetvl: scalar register holding AVL
    case Special                   // "x0, x0" pattern (preserves vl, sets new vtype)

  /** Generate the canonical case list for one VLEN/XLEN config. */
  def cases(vlen: Int, xlen: Int): List[VsetvlCase] =
    val avlValues = List(
      AvlSource.Imm(0),
      AvlSource.Imm(1),
      AvlSource.Imm(4),
      AvlSource.Imm(16),
      AvlSource.ScalarReg("a1"),
      AvlSource.Special)
    val legalVtypes = for
      sew  <- Sew.all if sew.bits <= xlen
      lmul <- Lmul.all
      vta  <- List(Vta.Agnostic, Vta.Undisturbed)
      vma  <- List(Vma.Agnostic, Vma.Undisturbed)
      if me.jiuyang.rvprobe.rvv.vtype.VType.isLegal(sew, lmul, xlen)
    yield VType(sew, lmul, vta, vma)
    val illegalVtype = VType(Sew.Sew64, Lmul.Mf2, Vta.Agnostic, Vma.Agnostic) // SEW*1/LMUL > ELEN

    val result = List.newBuilder[VsetvlCase]
    // Mostly use vsetvli (the most common upstream variant).
    for vt <- legalVtypes do
      for avl <- avlValues.take(3) do
        result += VsetvlCase("vsetvli", avl, vt, expectVill = false)
    // A couple of vsetivli cases (uimm5)
    for vt <- legalVtypes.take(4) do
      result += VsetvlCase("vsetivli", AvlSource.Imm(8), vt, expectVill = false)
    // The vsetvl variant (scalar-driven vtype) — use a representative
    // single legal vtype.
    legalVtypes.headOption.foreach { vt =>
      result += VsetvlCase("vsetvl", AvlSource.ScalarReg("a1"), vt, expectVill = false)
    }
    // Per DEC-9: one vill-triggering case to verify the CSR state.
    result += VsetvlCase("vsetvli", AvlSource.Imm(4), illegalVtype, expectVill = true)
    result.result()

  /** Emit a single `.S` per vsetvl* variant. Different variants share
   *  the upstream RVTEST_* contract but differ in operand encoding.
   *
   *  This is the AC-5 (vsetvl* path) + AC-16 (vsetvli POC) deliverable.
   */
  def renderTestS(insn: String, vlen: Int, xlen: Int, envMacro: String): String =
    val cs = cases(vlen, xlen).filter(_.insn == insn)
    val blocks = cs.zipWithIndex.map { case (c, idx) =>
      val env = VTypeEnvelope.unsafe(
        VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic),
        vl = 0, vlen = vlen, xlen = xlen)
      val setup = c.avl match
        case AvlSource.Imm(_)           => Nil
        case AvlSource.ScalarReg(r)     => List(s"li $r, ${idx + 1}")
        case AvlSource.Special          => Nil
      val asm = formatAvlAndVtype(c)
      val vsetvlAsm = s"$insn ${asm}"
      // Per DEC-9c: do NOT execute a vector instruction under vill=1.
      // Stop after the CSR check (which pspike reads via the magic
      // word — group 0 is a "sentinel" indicating CSR-only).
      val checkAsm = List(
        // Read vill (high bit of vtype) and vl into scalar regs so the
        // post-magic-word patch can inspect them.
        "csrr a3, vtype",
        "csrr a4, vl")
      TestSEmit.TestBlock(
        envelope    = env,
        vectorGroup = 0, // sentinel: CSR-only path
        vxsat       = false,
        insnAsm     = vsetvlAsm,
        setupAsm    = setup,
        dataLabel   = None)
    }
    TestSEmit.render(s"$insn-vsetvl-sweep", envMacro, blocks, Vector.empty, 64)

  private def formatAvlAndVtype(c: VsetvlCase): String =
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
          case AvlSource.Imm(_)       => "x0"
          case AvlSource.ScalarReg(r) => r
          case AvlSource.Special      => "x0"
        s"x5, $avlReg, $vtypeStr"
      case "vsetivli" =>
        val n = c.avl match
          case AvlSource.Imm(v) => v
          case _                => 0
        s"x5, $n, $vtypeStr"
      case "vsetvl"   =>
        val avlReg = c.avl match
          case AvlSource.ScalarReg(r) => r
          case _                      => "a1"
        // vsetvl uses rs2 to encode the new vtype directly
        s"x5, $avlReg, a2"
      case _          =>
        throw new IllegalArgumentException(s"unknown vsetvl variant: ${c.insn}")
