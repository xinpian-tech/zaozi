// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import java.util.regex.Pattern

/** Brings a SystemVerilog-only IP into the formal flow: `circt-verilog` imports the RTL to the HW dialect, [[toHw]]
  * adapts it for circt-bmc, and [[mergeForBmc]] splices the definition into a zaozi wrapper's lowered module in place
  * of its `hw.module.extern` — so a typed-DSL constraint solves *through* external RTL the same way it does through a
  * zaozi DUT. (The replay side needs none of this: the testbench build takes the RTL sources directly.)
  */
object SvImport:

  /** Import SV sources via `circt-verilog --ir-hw` and adapt the result for circt-bmc. */
  def toHw(sources: Seq[os.Path], outDir: os.Path, include: Option[os.Path] = None): os.Path =
    os.makeDir.all(outDir)
    val raw = outDir / "imported.hw.mlir"
    os.write.over(
      raw,
      os.proc(
        Seq(CirctTools("circt-verilog")) ++ sources.map(_.toString) ++ Seq("--ir-hw") ++
          include.toSeq.flatMap(p => Seq("-I", p.toString))
      ).call(check = true)
        .out
        .text()
    )
    adaptForBmc(raw)

  /** Adaptations on the imported IR, textual on the printed form:
    *
    *   1. Clock ports arrive as plain `i1` fed through an internal `seq.to_clock`, while a zaozi wrapper's extern
    *      instance drives `!seq.clock` — retype the port (including instance-argument annotations) and use it
    *      directly.
    *   2. Four-state comparison predicates (case/wildcard equality from `==`/`===` on logic types) coincide with
    *      plain eq/ne under the two-state semantics the bounded model works in — and circt-bmc cannot lower the
    *      four-state forms.
    *   3. Registers arrive with reset folded into their input mux and NO initial value — [[MlirBmc.pinFirregs]] pins
    *      them (the caller must model the RTL's reset preamble explicitly, so the
    *      pinned zero only has to match Verilator's two-state startup).
    *   4. Small `seq.firmem` memories are expanded into per-slot registers ([[expandFirmems]]).
    */
  private[utlib] def adaptForBmc(hwMlir: os.Path): os.Path =
    var lines = os.read(hwMlir).split("\n", -1).toVector

    // 1. seq.to_clock: retype the source port, drop the conversion, use the port everywhere.
    //
    // Scoped to one module at a time: several modules of a hierarchy commonly have a port named `clk`, and a
    // file-wide rename makes two unrelated values collide on the same SSA name (seen on the three-module I2C).
    lines = Vector(lines.head) ++ MlirBmc
      .topEntities(lines)
      .flatMap((s, e) => retypeClocks(lines.slice(s, e + 1)))
      ++ Vector("}", "")

    // 2. Two-state comparison predicates.
    lines = lines.map(
      _.replace("comb.icmp ceq ", "comb.icmp eq ")
        .replace("comb.icmp cne ", "comb.icmp ne ")
        .replace("comb.icmp weq ", "comb.icmp eq ")
        .replace("comb.icmp wne ", "comb.icmp ne ")
    )

    // 3 + 4. Pinning is per module entity: constant names are module-scoped.
    lines = Vector(lines.head) ++ MlirBmc
      .topEntities(lines)
      .flatMap((s, e) => MlirBmc.pinFirregs(lines.slice(s, e + 1)))
      ++ Vector("}", "")
    lines = expandFirmems(lines)

    val out = hwMlir / os.up / s"${hwMlir.last}.bmc.mlir"
    os.write.over(out, lines.mkString("\n"))
    out

  private val toClock = raw"^\s*(%[\w.$$]+) = seq\.to_clock (%[\w.$$]+)\s*$$".r

  /** Within one module: retype each clock port to `!seq.clock`, drop its `seq.to_clock`, and use the port directly. */
  private def retypeClocks(block: Vector[String]): Vector[String] =
    var out = block
    for (converted, port) <- block.flatMap(l => toClock.findFirstMatchIn(l).map(m => m.group(1) -> m.group(2))) do
      val use = Pattern.compile(raw"${Pattern.quote(converted)}\b")
      out = out.flatMap { l =>
        if toClock.findFirstMatchIn(l).exists(_.group(1) == converted) then None
        else
          Some(
            use
              .matcher(
                l.replace(s"in $port : i1", s"in $port : !seq.clock")
                  // an hw.instance passing the retyped value annotates the operand type inline: `clk: %clk_i: i1`
                  .replace(s"$port: i1", s"$port: !seq.clock")
              )
              .replaceAll(port)
          )
      }
    out

  private val memDef    = raw"^(\s*)(%[\w.$$/]+) = seq\.firmem [^:]+: <(\d+) x (\d+), mask \d+>.*$$".r
  private val readPort  =
    raw"^\s*(%[\w.$$/]+) = seq\.firmem\.read_port (%[\w.$$/]+)\[(%[\w.$$/]+)\], clock (%[\w.$$/]+) : .*$$".r
  private val writePort =
    raw"^\s*seq\.firmem\.write_port (%[\w.$$/]+)\[(%[\w.$$/]+)\] = (%[\w.$$/]+), clock (%[\w.$$/]+) enable (%[\w.$$/]+) : .*$$".r

  /** Expand each small `seq.firmem` (e.g. a FIFO's storage) with exactly one combinational read port and one write
    * port into per-slot registers: a zero-pinned compreg per slot, a write-enable mux per slot, and a read mux tree —
    * circt-bmc cannot legalize the memory primitive. Any other port shape fails loudly here rather than as an opaque
    * solver parse error.
    */
  private def expandFirmems(input: Vector[String]): Vector[String] =
    val mems   = input.collect { case memDef(_, m, depth, width) => m -> (depth.toInt, width.toInt) }.toMap
    if mems.isEmpty then return input
    val reads  = input.collect { case readPort(res, m, addr, _) => m -> (res, addr) }.toMap
    val writes = input.collect { case writePort(m, addr, data, clk, en) => m -> (addr, data, clk, en) }.toMap
    for m <- mems.keys do
      require(
        reads.contains(m) && writes.contains(m),
        s"seq.firmem $m needs exactly one read port and one write port to be expanded for circt-bmc"
      )

    input.flatMap { l =>
      l match
        case memDef(ind, m, _, _)  =>
          val (depth, width)            = mems(m)
          val aw                        = 32 - Integer.numberOfLeadingZeros(depth - 1)
          val (_, raddr)                = reads(m)
          val (waddr, wdata, wclk, wen) = writes(m)
          val n                         = m.drop(1).replace('/', '_')
          val slots                     = (0 until depth).flatMap { i =>
            Vector(
              s"$ind%${n}__a$i = hw.constant $i : i$aw",
              s"$ind%${n}__weq$i = comb.icmp eq $waddr, %${n}__a$i : i$aw",
              s"$ind%${n}__we$i = comb.and %${n}__weq$i, $wen : i1",
              s"$ind%${n}__n$i = comb.mux %${n}__we$i, $wdata, %${n}__s$i : i$width"
            ) ++ MlirBmc.pinnedReg(ind, s"%${n}__s$i", s"%${n}__n$i", wclk, s"i$width", MlirBmc.zeroConst(s"i$width"))
          }
          val readMux                   = (1 until depth).flatMap { i =>
            val prev = if i == 1 then s"%${n}__s0" else s"%${n}__r${i - 1}"
            Vector(
              s"$ind%${n}__req$i = comb.icmp eq $raddr, %${n}__a$i : i$aw",
              s"$ind%${n}__r$i = comb.mux %${n}__req$i, %${n}__s$i, $prev : i$width"
            )
          }
          slots ++ readMux
        case readPort(res, m, _, _) =>
          val (depth, width) = mems(m)
          val n              = m.drop(1).replace('/', '_')
          // The read port becomes an SSA alias of the mux tree's root (a self-OR: pure text can't rename uses).
          Vector(s"    $res = comb.or %${n}__r${depth - 1}, %${n}__r${depth - 1} : i$width")
        case writePort(_, _, _, _, _) => Vector.empty
        case _                        => Vector(l)
    }

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

    val rename       = Pattern.compile(raw"@${Pattern.quote(importedTop)}\b")
    val importedBody = imported
      .dropWhile(!_.trim.startsWith("module"))
      .drop(1)
      .reverse
      .dropWhile(_.trim != "}")
      .drop(1)
      .reverse
      .map(l => rename.matcher(l).replaceAll(s"@$externSym"))

    val keptWrapper = wrapper.filterNot(_.contains(s"hw.module.extern @$externSym"))
    val closingIdx  = keptWrapper.lastIndexWhere(_.trim == "}")
    val merged      = keptWrapper.take(closingIdx) ++ importedBody ++ keptWrapper.drop(closingIdx)
    val out         = wrapperHw / os.up / s"${wrapperHw.last}.merged.mlir"
    os.write.over(out, merged.mkString("\n"))
    out
