// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import java.util.regex.Pattern

/** The one home for making printed HW-dialect IR ingestible by circt-bmc.
  *
  * Every transform here is textual and structural on the printed form (the pragmatic fallback where no circt-opt pass
  * exists — `--strip-contracts` shows the preferred shape). Shared invariants live here once: how a register's initial
  * state is pinned, how top-level entities are delimited, and the naming contract between the drive-delay register and
  * the trace reader.
  */
private[utlib] object MlirBmc:

  /** The naming contract between [[FormalUT.delayInputs]] (which creates `%<drive><suffix>`) and [[Stimulus]] (which
    * reads the register's `<drive><suffix>_next` trace signal).
    */
  val DelayedDriveSuffix = "__d"

  private val ssa = raw"%[\w.$$/]+"

  /** The pinned-register idiom circt-bmc's externalize-registers honors: a `seq.initial`-wrapped constant feeding
    * `seq.compreg … initial`. This is the only place the textual shape exists.
    */
  def pinnedReg(ind: String, name: String, input: String, clock: String, ty: String, const: String): Vector[String] =
    Vector(
      s"$ind${name}__init = seq.initial() {",
      s"$ind  ${name}__c = hw.constant $const",
      s"$ind  seq.yield ${name}__c : $ty",
      s"$ind} : () -> !seq.immutable<$ty>"
    ) :+ s"$ind$name = seq.compreg $input, $clock initial ${name}__init : $ty"

  /** Zero in the printed constant form: 1-bit constants print bare, wider ones carry the type. */
  def zeroConst(ty: String): String = if ty == "i1" then "false" else s"0 : $ty"

  // `hw.constant` definitions, both printed forms.
  private val constDef = raw"($ssa) = hw.constant ([-\d]+ : [\w<>]+|true|false)".r
  // Any firreg: optional inner sym, optional sync reset clause, optional attribute dict.
  private val firreg   =
    raw"^(\s*)($ssa) = seq\.firreg ($ssa) clock ($ssa)(?: sym @[\w.]+)?(?: reset sync ($ssa), ($ssa))?(?: \{[^}]*\})? : (\S+)$$".r

  private def constTy(text: String): String = if text == "true" || text == "false" then "i1" else text.split(" : ").last

  /** Pin every integer-typed `seq.firreg`'s initial state, within one module's lines.
    *
    * circt-bmc leaves register initial state FREE — a solver can then satisfy a cross-cycle constraint by inventing
    * pre-loaded history instead of driving real inputs. A sync-reset firreg (zaozi `RegInit`, or sync-ified imported
    * RTL) pins to its constant reset value, with the reset re-expressed as a mux on the input; a reset-less firreg
    * (zaozi `Reg`, or imported RTL whose reset folded into the data path) pins to zero — Verilator's two-state
    * default, so model and replay start identically. Async resets, non-constant reset values, and aggregate types are
    * left alone. hw.module bodies are graph regions, so inserted lines need no dominance ordering.
    */
  def pinFirregs(block: Vector[String]): Vector[String] =
    val consts = block.flatMap(l => constDef.findFirstMatchIn(l.trim).map(m => m.group(1) -> m.group(2))).toMap
    block.flatMap {
      case l @ firreg(ind, r, in, clk, rst, rv, ty) =>
        (Option(rst), Option(rv).flatMap(consts.get)) match
          case (Some(rstV), Some(constText)) if constTy(constText) == ty =>
            // Sync reset becomes a mux on the input; the init is the reset constant.
            val mux = s"$ind${r}__mux = comb.mux $rstV, ${rv}, $in : $ty"
            pinnedReg(ind, r, s"${r}__mux", clk, ty, constText).patch(4, Vector(mux), 0)
          case (None, _) if ty.startsWith("i")                            =>
            pinnedReg(ind, r, in, clk, ty, zeroConst(ty))
          case _                                                          => Vector(l)
      case l                                                              => Vector(l)
    }

  /** The line index of the matching close brace for the entity starting at `startIdx`. */
  def entityEnd(lines: Vector[String], startIdx: Int): Int =
    var depth = 0
    var i     = startIdx
    while
      depth += lines(i).count(_ == '{') - lines(i).count(_ == '}')
      depth > 0
    do i += 1
    i

  /** Top-level entities of a printed `module { … }`: contiguous [start, end] line ranges at brace depth 1. */
  def topEntities(lines: Vector[String]): Vector[(Int, Int)] =
    val entities = Vector.newBuilder[(Int, Int)]
    var depth    = 0
    var start    = -1
    for (l, i) <- lines.zipWithIndex do
      val net = l.count(_ == '{') - l.count(_ == '}')
      if depth == 1 && start < 0 && l.trim.nonEmpty && l.trim != "}" then start = i
      depth += net
      if start >= 0 && depth <= 1 then
        entities += ((start, i))
        start = -1
    entities.result()

  // Precompiled per-line scrubbers (a lowered module can run to tens of thousands of lines).
  private val namehintAttr  = Pattern.compile(""" \{sv\.namehint = "[^"]*"\}""")
  private val namehintEntry = Pattern.compile("""sv\.namehint = "[^"]*", """)
  private val wireSym       = Pattern.compile(""" sym @[\w.]+\s+""")

  def stripNamehints(l: String): String =
    namehintEntry.matcher(namehintAttr.matcher(l).replaceAll("")).replaceAll("")

  def stripWireSym(l: String): String =
    if l.contains("hw.wire") then wireSym.matcher(l).replaceAll(" ") else l
