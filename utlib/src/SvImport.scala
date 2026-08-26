// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

/** Brings a SystemVerilog-only IP into the formal flow: `circt-verilog` imports the RTL to the HW dialect, [[toHw]]
  * adapts it for circt-bmc, and [[mergeForBmc]] splices the definition into a zaozi wrapper's lowered module in place
  * of its `hw.module.extern` — so a typed-DSL constraint solves *through* external RTL the same way it does through a
  * zaozi DUT. (The replay side needs none of this: the wrapper's `verilogSources` carries the RTL to the testbench.)
  */
object SvImport:

  /** Import SV sources via `circt-verilog --ir-hw` and adapt the result for circt-bmc. */
  def toHw(sources: Seq[os.Path], outDir: os.Path): os.Path =
    os.makeDir.all(outDir)
    val raw = outDir / "imported.hw.mlir"
    os.write.over(
      raw,
      os.proc(Seq(CirctTools("circt-verilog")) ++ sources.map(_.toString) ++ Seq("--ir-hw"))
        .call(check = true)
        .out
        .text()
    )
    adaptForBmc(raw)

  /** Two adaptations on the imported IR, both textual on the printed form:
    *
    *   1. Clock ports arrive as plain `i1` fed through an internal `seq.to_clock`, while a zaozi wrapper's extern
    *      instance drives `!seq.clock` — retype the port and use it directly.
    *   2. Registers arrive with reset folded into their input mux and NO initial value; circt-bmc would leave that
    *      initial state free, letting a witness carry fake pre-loaded history. Pin the initial value to zero — the
    *      assumed post-reset state (an IP whose registers reset to something else needs its own treatment) — with the
    *      caller assuming the reset input low so the model tracks the testbench's post-reset run.
    */
  private[utlib] def adaptForBmc(hwMlir: os.Path): os.Path =
    var lines = os.read(hwMlir).split("\n", -1).toVector

    // 1. seq.to_clock: retype the source port, drop the conversion, use the port everywhere.
    val toClock = raw"^\s*(%[\w.$$]+) = seq\.to_clock (%[\w.$$]+)\s*$$".r
    val convs   = lines.flatMap(l => toClock.findFirstMatchIn(l).map(m => m.group(1) -> m.group(2)))
    for (converted, port) <- convs do
      lines = lines
        .filterNot(l => toClock.findFirstMatchIn(l).exists(_.group(1) == converted))
        .map(_.replace(s"in $port : i1", s"in $port : !seq.clock"))
        .map(_.replaceAll(raw"${java.util.regex.Pattern.quote(converted)}\b", port))

    // 2. Reset-less firregs: pin the initial state to zero.
    val firreg = raw"^(\s*)(%[\w.$$]+) = seq\.firreg (%[\w.$$]+) clock (%[\w.$$]+) : (\S+)$$".r
    lines = lines.flatMap {
      case l @ firreg(ind, r, in, clk, ty) =>
        val zero = if ty == "i1" then "false" else s"0 : $ty"
        Vector(
          s"$ind${r}__init = seq.initial() {",
          s"$ind  ${r}__c = hw.constant $zero",
          s"$ind  seq.yield ${r}__c : $ty",
          s"$ind} : () -> !seq.immutable<$ty>",
          s"$ind$r = seq.compreg $in, $clk initial ${r}__init : $ty"
        )
      case l                               => Vector(l)
    }

    val out = hwMlir / os.up / s"${hwMlir.last}.bmc.mlir"
    os.write.over(out, lines.mkString("\n"))
    out

  /** Splice the imported definition into the wrapper's lowered module: the wrapper's single `hw.module.extern` is
    * dropped and the imported top module renamed to that extern's symbol, so the wrapper's instance binds to real
    * logic.
    */
  def mergeForBmc(wrapperHw: os.Path, importedHw: os.Path): os.Path =
    val wrapper   = os.read(wrapperHw).split("\n", -1).toVector
    val imported  = os.read(importedHw).split("\n", -1).toVector
    val externSym = wrapper
      .flatMap(raw"hw\.module\.extern @([\w.$$]+)\(".r.findFirstMatchIn(_))
      .map(_.group(1))
      .distinct match
      case Seq(one) => one
      case other    => throw IllegalArgumentException(s"expected exactly one extern in $wrapperHw, got $other")
    val importedTop = imported
      .flatMap(raw"hw\.module @([\w.$$]+)\(".r.findFirstMatchIn(_))
      .headOption
      .getOrElse(throw IllegalArgumentException(s"no hw.module in $importedHw"))
      .group(1)

    val importedBody = imported
      .dropWhile(!_.trim.startsWith("module"))
      .drop(1)
      .reverse
      .dropWhile(_.trim != "}")
      .drop(1)
      .reverse
      .map(_.replaceAll(raw"@${java.util.regex.Pattern.quote(importedTop)}\b", s"@$externSym"))

    val keptWrapper  = wrapper.filterNot(_.contains(s"hw.module.extern @$externSym"))
    val closingIdx   = keptWrapper.lastIndexWhere(_.trim == "}")
    val merged       = keptWrapper.take(closingIdx) ++ importedBody ++ keptWrapper.drop(closingIdx)
    val out          = wrapperHw / os.up / s"${wrapperHw.last}.merged.mlir"
    os.write.over(out, merged.mkString("\n"))
    out
