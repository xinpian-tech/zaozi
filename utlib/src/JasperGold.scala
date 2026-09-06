// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*

/** A UT lowered for a SystemVerilog formal engine: firtool's single-file SV of the linked design (zaozi's
  * `assert property (not (…))` and `assume property` emitted as-is), the external RTL the wrapper's extern binds to,
  * and the top's clock and reset port names. Generation assertions must be named explicitly; ordinary assertions
  * and external RTL covers are never interpreted as generation requests.
  */
final case class JgModel(
  sv:      os.Path,
  top:     String,
  rtl:     Seq[os.Path],
  generationLabels: Set[String],
  include: Option[os.Path] = None,
  clock:   String = "clock",
  reset:   String = "reset"):
  require(generationLabels.nonEmpty, "generation labels must not be empty")
  require(generationLabels.forall(_.matches("[A-Za-z_][A-Za-z0-9_]*")), "invalid generation label")

/** The second formal backend: JasperGold, for the depth circt-bmc does not reach.
  *
  * The same generation reading as [[FormalUT]] — the UT asserts `¬C`, a counterexample is a witness where `C`
  * holds — but on the SystemVerilog firtool emits rather than on pruned HW IR: no register pinning, no layer
  * stripping, no import surgery, and the property language is whatever SVA the engine accepts (bounded and
  * unbounded delays, repetition, `throughout`, `until`). A `proven` verdict is unbounded, so `Infeasible` here means
  * "no trace at any depth", not "none within the bound".
  *
  * The engine runs through `ZAOZI_EDA_SHELL` when set (a wrapper invoked as `<shell> -c "<command>"` that provides the
  * license environment — see `experiments/eda-shell`), else `jg` from `PATH`. The counterexample is dumped
  * as VCD and read back per rising clock edge into the same [[Trace]] shape circt-bmc's parser produces: top-level
  * ports by name, internal signals by `scope/…/name` below the top.
  */
object JasperGold:

  /** Is an engine reachable — a wrapper configured, or `jg` on the path? Tests skip rather than fail without one. */
  lazy val available: Boolean =
    sys.env.get("ZAOZI_EDA_SHELL").exists(p => os.exists(os.Path(p, os.pwd))) ||
      os.proc("sh", "-c", "command -v jg").call(check = false).exitCode == 0

  /** Elaborate + `firld` link a UT generator and emit it as one SystemVerilog file. */
  def lower[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](dut:       Generator[PARAM, L, I, P],
    parameter: PARAM,
    outDir:    os.Path,
    rtl:       Seq[os.Path],
    generationLabels: Set[String],
    include:   Option[os.Path] = None
  ): JgModel =
    os.makeDir.all(outDir)
    val top       = dut.moduleName(parameter)
    val moduleDir = outDir / s"jg_mlir_${parameter.hashCode.toHexString}"
    val linked    = Lower.elaborateAndLink(dut, parameter, moduleDir, top)
    val sv        = outDir / s"$top.sv"
    // Keep generation assertions even when their condition folds to true (an impossible goal).
    // Dropping such an assertion loses the distinction between unreachable and a missing property.
    // The external DUT RTL is unchanged, and JasperGold still optimizes the elaborated model.
    os.proc(CirctTools("firtool"), "--disable-opt", linked.toString, "-o", sv.toString).call(check = true)
    JgModel(sv, top, rtl, generationLabels, include)

  /** Generate a witness for the UT's intent. `timeLimit` is JasperGold's per-run proof limit.
    *
    * The generation reading is stated to the engine as what it is — a **cover** of the scenario — rather than as
    * the `assert ¬C` circt-bmc needs: firtool's `assert property (not (S))` is rewritten to `cover property ((S))`
    * before analysis. Same semantics, different engines: a cover is a reachability search, and an unreachable
    * scenario is *proven* so ([[GenerateOutcome.Infeasible]] here is unbounded), whereas the assertion form leaves a
    * hard-to-refute `not` to the proof engines — on the i2c flow, `undetermined` after 600 s against `unreachable`
    * in 2 s for the identical scenario.
    */
  def generate(model: JgModel, workDir: os.Path, timeLimit: String = "600s"): GenerateOutcome =
    os.makeDir.all(workDir)
    os.remove.all(workDir / "jgproj") // a project directory left by an interrupted run refuses a new session
    val vcd     = workDir / "witness.vcd"
    val sv      = workDir / model.sv.last
    os.write.over(sv, asCover(os.read(model.sv), model.generationLabels))
    val include = model.include.map(p => s"+incdir+$p ").getOrElse("")
    // Vendored `.v` files are read as Verilog-2001 and everything else as SystemVerilog: legacy RTL uses SV keywords
    // as identifiers (the CAN controller has a port named `do`), which is also why HAVEN's VCS line carries
    // `+verilog2001ext+.v`.
    val (v2k, sv12) = (model.rtl :+ sv).partition(_.ext == "v")
    val analyze     = Seq(
      Option.when(v2k.nonEmpty)(s"analyze -v2k $include${v2k.map(_.toString).mkString(" ")}"),
      Option.when(sv12.nonEmpty)(s"analyze -sv12 $include${sv12.map(_.toString).mkString(" ")}")
    ).flatten.mkString("\n")
    // External RTL may itself contain covers. Only a selected generation property can supply this witness.
    val selected = model.generationLabels.toSeq.sorted.map { label =>
      require(label.matches("[A-Za-z_][A-Za-z0-9_]*"), s"unsupported generation label: $label")
      s"[string match {*::${model.top}.$label} $$p] || [string equal {${model.top}.$label} $$p]"
    }.mkString(" || ")
    val filter = s"if {!($selected)} { continue }"
    val tcl     = workDir / "generate.tcl"
    os.write.over(
      tcl,
      s"""|clear -all
          |$analyze
          |elaborate -top ${model.top}
          |clock ${model.clock}
          |reset ${model.reset}
          |set_prove_time_limit $timeLimit
          |prove -all
          |set found ""
          |foreach p [get_property_list -include {type cover}] {
          |  if {[string match "*:live" $$p]} { continue }
          |  $filter
          |  set st [get_property_info $$p -list status]
          |  puts "JGSTATUS $$p $$st"
          |  if {$$st == "covered" && $$found == ""} { set found $$p }
          |}
          |if {$$found != ""} {
          |  visualize -cover -property $$found -window visualize:0
          |  visualize -save -vcd $vcd -force -window visualize:0
          |  puts "JGCOVERED $$found"
          |}
          |puts "JGDONE"
          |exit
          |""".stripMargin
    )
    val cmd = s"cd $workDir && jg -batch -tcl $tcl -proj jgproj"
    val run = sys.env.get("ZAOZI_EDA_SHELL") match
      case Some(shell) => os.proc(shell, "-c", cmd).call(check = false, stderr = os.Pipe)
      case None        => os.proc("sh", "-c", cmd).call(check = false, stderr = os.Pipe)
    val log = run.out.text() + "\n" + run.err.text()
    os.write.over(workDir / "jg.log", log)

    val statuses = log.linesIterator.collect { case s"JGSTATUS $name $status" => name.trim -> status.trim }.toSeq
    if !log.contains("JGDONE") then GenerateOutcome.Unknown(s"jg did not finish: ${log.linesIterator.toSeq.takeRight(5).mkString(" | ")}")
    else if os.exists(vcd) && statuses.exists(_._2 == "covered") then
      val trace = withAliases(parseVcd(vcd, model.clock), svAliases(model.sv, model.top))
      GenerateOutcome.Generated(withFreeInputs(trace, svInputs(model.sv, model.top)))
    else if statuses.nonEmpty && statuses.forall(_._2 == "unreachable") then GenerateOutcome.Infeasible
    else GenerateOutcome.Unknown(s"cover statuses: ${statuses.map((n, s) => s"$n=$s").mkString(", ")}")

  /** Turn explicitly selected generation assertions into covers of their negation. */
  private[utlib] def asCover(sv: String, labels: Set[String]): String =
    // A runtime UT identifies its generation properties explicitly. Negate the emitted assertion,
    // not its source spelling: firtool may fold !done to ~done, !(!done) to done, or a constant.
    // Never reinterpret an unrelated assertion as a generation request.
    require(labels.nonEmpty, "generation labels must not be empty")
    val found = collection.mutable.Set.empty[String]
    val result = raw"(?s)(\w+):((?:[^\n]*\n)?\s*)assert property \((.*?)\);".r.replaceAllIn(
      sv,
      m => java.util.regex.Matcher.quoteReplacement(
        if labels.contains(m.group(1)) then
          require(found.add(m.group(1)), s"ambiguous generation label: ${m.group(1)}")
          s"${m.group(1)}:${m.group(2)}cover property (not (${m.group(3)}));"
        else m.matched
      )
    )
    require(found.toSet == labels, s"generation labels missing from emitted assertions: ${labels -- found}")
    result

  /** The window JasperGold dumps holds the property's cone, under the names the cone uses — a top-level output
    * that merely forwards an instance pin appears as the pin, not the port. firtool's SV states every such
    * forwarding (`assign PORT = net;`, and `.pin(net)` on the instance), so read the top module's wiring once and
    * make each alias resolvable: a trace column for `PORT` is a copy of the column its net or pin has.
    */
  private[utlib] def svAliases(sv: os.Path, top: String): Map[String, String] =
    val text  = os.read(sv)
    val start = text.indexOf(s"module $top(")
    val body  = if start < 0 then text else text.substring(start, text.indexOf("endmodule", start).max(start))
    val assigns  = raw"assign\s+(\w+)\s*=\s*(\w+)\s*;".r.findAllMatchIn(body).map(m => m.group(1) -> m.group(2)).toSeq
    val inst     = raw"(?s)\n\s*(\w+)\s+(\w+)\s*\((.*?)\);".r.findAllMatchIn(body).toSeq
    val pins     = inst.flatMap { m =>
      val instName = m.group(2)
      raw"\.(\w+)\s*\((\w+)\)".r.findAllMatchIn(m.group(3)).map(p => p.group(2) -> s"$instName/${p.group(1)}")
    }
    (assigns ++ pins).toMap

  /** The top module's input ports, from firtool's SV header — where a direction keyword carries over the following
    * lines (`input clock,` then `reset,`), so the direction is tracked line by line rather than matched per port.
    */
  private[utlib] def svInputs(sv: os.Path, top: String): Seq[String] =
    val text  = os.read(sv)
    val start = text.indexOf(s"module $top(")
    if start < 0 then Seq.empty
    else
      val lines = text.substring(start).linesIterator.drop(1).takeWhile(_.trim != ");").toSeq
      var dir   = ""
      val names = Seq.newBuilder[String]
      for l <- lines do
        val body = l.split("//", 2).head.trim
        val dirM = raw"^(input|output|inout)\b".r.findFirstMatchIn(body)
        dirM.foreach(m => dir = m.group(1))
        val rest = dirM.fold(body)(m => body.substring(m.end)).trim
        raw"^(?:\[[^\]]*\]\s*)?(\w+)".r.findFirstMatchIn(rest).foreach(m => if dir == "input" then names += m.group(1))
      names.result()

  /** An input the property never mentions is absent from the dumped window: the engine left it free, so any value
    * completes the witness, and zero is the canonical choice — the same one circt-bmc's trace parser makes.
    */
  private[utlib] def withFreeInputs(trace: Trace, inputs: Seq[String]): Trace =
    val free = inputs.filterNot(trace.values.contains).map(_ -> Vector.fill(trace.cycles)(BigInt(0)))
    trace.copy(values = trace.values ++ free)

  /** Resolve `aliases` (name → other name, chained) against the trace's columns, adding what is missing. */
  private[utlib] def withAliases(trace: Trace, aliases: Map[String, String]): Trace =
    def resolve(name: String, depth: Int = 0): Option[Vector[BigInt]] =
      trace.values.get(name).orElse(if depth > 4 then None else aliases.get(name).flatMap(resolve(_, depth + 1)))
    val added = aliases.keys.filterNot(trace.values.contains).flatMap(n => resolve(n).map(n -> _)).toMap
    trace.copy(values = trace.values ++ added)

  /** Read a VCD into a per-cycle trace: one row per rising edge of `clock`, every signal sampled just before the
    * edge (the value the edge sees). Vectors and scalars parse to integers; `x`/`z` bits read as 0. Top-level
    * signals keep their name; deeper ones are `scope/…/name` with the top module's scope dropped.
    */
  private[utlib] def parseVcd(path: os.Path, clock: String): Trace =
    val lines   = os.read.lines(path)
    val names   = scala.collection.mutable.Map.empty[String, Vector[String]] // id -> signal names (aliases)
    val scope   = scala.collection.mutable.ArrayBuffer.empty[String]
    var i       = 0
    var inDefs  = true
    while inDefs && i < lines.length do
      val t = lines(i).trim.split("\\s+")
      t.headOption match
        case Some("$scope")          => scope += t(2)
        case Some("$upscope")        => scope.remove(scope.length - 1)
        case Some("$var")            =>
          val name = (scope.drop(1) :+ t(4)).mkString("/")
          names(t(3)) = names.getOrElse(t(3), Vector.empty) :+ name
        case Some("$enddefinitions") => inDefs = false
        case _                       => ()
      i += 1
    val clockId = names.collectFirst { case (id, ns) if ns.contains(clock) => id }
      .getOrElse(throw IllegalArgumentException(s"clock '$clock' not in $path"))

    val current = scala.collection.mutable.Map.empty[String, BigInt]
    val rows    = scala.collection.mutable.ArrayBuffer.empty[Map[String, BigInt]]
    // JasperGold's dump opens at time 0 with the clock already high after the full initial state: that IS the first
    // rising edge, so the clock is taken as low before the file starts (the first beat is otherwise lost).
    var prevClk = BigInt(0)
    def value(bits: String): BigInt = BigInt(bits.map(c => if c == '1' then '1' else '0'), 2)
    while i < lines.length do
      val l = lines(i).trim
      if l.nonEmpty && l.head != '#' && l.head != '$' then
        val (v, id) =
          if l.head == 'b' || l.head == 'B' then
            val Array(bits, id) = l.substring(1).split("\\s+", 2)
            (value(bits), id)
          else (value(l.take(1)), l.drop(1))
        if id == clockId then
          if v == 1 && prevClk == 0 then rows += current.toMap
          prevClk = v
        current(id) = v
      i += 1
    val ids     = names.keys.toVector
    val columns = for id <- ids; name <- names(id) yield name -> rows.map(_.getOrElse(id, BigInt(0))).toVector
    Trace(rows.length, columns.toMap)
