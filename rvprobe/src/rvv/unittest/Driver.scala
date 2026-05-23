// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.unittest

import me.jiuyang.rvprobe.rvv.eew.OperandWidthProfile
import me.jiuyang.rvprobe.rvv.pred.{TuplePred, ValuePred}
import me.jiuyang.rvprobe.rvv.vtype.{Lmul, Sew, VType, VTypeEnvelope, Vma, Vta}
import me.jiuyang.rvprobe.rvv.{OperandRole, Schema}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Replaces the upstream `riscv-vector-tests/main.go`. Walks the
 *  RvvInsn registry, applies the upstream CLI flags (`-VLEN`, `-XLEN`,
 *  `-split`, `-integer`, `-pattern`, `-stage1output`, `-testfloat3level`,
 *  `-repeat`, `-march`), and emits per-instruction `.S` files +
 *  sorted Makefrag.
 *
 *  Per AC-12, this driver reads no `.toml` at runtime. Per DEC-3, the
 *  upstream Makefile gets a single-line generator-path swap to invoke
 *  this driver instead of the Go binary.
 */
object Driver:

  /** Parsed CLI flags. Defaults match upstream `main.go`. */
  final case class Cli(
    vlen:             Int     = 256,
    xlen:             Int     = 64,
    splitLines:       Int     = 10000,
    integerOnly:      Boolean = false,
    pattern:          String  = ".*",
    stage1OutputDir:  String  = "",
    testfloat3Level:  Int     = 2,
    repeat:           Int     = 1,
    march:            String  =
      "gcv_zvbb_zvbc_zfh_zvfh_zvkg_zvkned_zvknha_zvksed_zvksh_zfbfmin_zvfbfmin_zvfbfwma",
    configsIgnored:   Boolean = false // -configs is deprecated under rvprobe (DEC-5: silently ignore)
  )

  /** Parse args in the upstream-compatible style. Supports BOTH
   *  `-flag value` (space-separated) and `-flag=value` (Go's flag-pkg
   *  default) forms — the upstream Makefile uses the `=` form, the
   *  upstream README documents the space-separated form, so the driver
   *  must accept both. Boolean flags additionally accept
   *  `-integer=true/false`.
   *
   *  Round-7 Codex review confirmed this round-trips with the actual
   *  Makefile invocation: `-split=${SPLIT} -integer=${INTEGER} ...`.
   */
  def parseCli(args: Array[String]): Either[String, Cli] =
    var cli = Cli()
    var i   = 0
    val n   = args.length
    val err = List.newBuilder[String]

    /** Pull the value for `-flag value` or `-flag=value`. Returns
     *  (value, tokensConsumed). For boolean flags without `=`, returns
     *  ("", 1).
     */
    def pullValue(arg: String, key: String): (String, Int) =
      val eqIdx = arg.indexOf('=')
      if eqIdx >= 0 then (arg.substring(eqIdx + 1), 1)
      else if i + 1 < n then (args(i + 1), 2)
      else throw new IllegalArgumentException(s"missing value after $key")

    def pullInt(arg: String, key: String): (Int, Int) =
      val (v, consumed) = pullValue(arg, key)
      (v.toInt, consumed)

    def pullBool(arg: String, key: String): (Boolean, Int) =
      // `-integer` (no value) -> true; `-integer=true|false` -> explicit
      val eqIdx = arg.indexOf('=')
      if eqIdx >= 0 then (arg.substring(eqIdx + 1) == "true", 1)
      else (true, 1)

    /** Match a flag name allowing `-flag` or `-flag=...`. */
    def matches(arg: String, key: String): Boolean =
      arg == key || arg.startsWith(s"$key=")

    while i < n do
      val a = args(i)
      if matches(a, "-VLEN") then
        val (v, c) = pullInt(a, "-VLEN"); cli = cli.copy(vlen = v); i += c
      else if matches(a, "-XLEN") then
        val (v, c) = pullInt(a, "-XLEN"); cli = cli.copy(xlen = v); i += c
      else if matches(a, "-split") then
        val (v, c) = pullInt(a, "-split"); cli = cli.copy(splitLines = v); i += c
      else if matches(a, "-integer") then
        val (v, c) = pullBool(a, "-integer"); cli = cli.copy(integerOnly = v); i += c
      else if matches(a, "-pattern") then
        val (v, c) = pullValue(a, "-pattern"); cli = cli.copy(pattern = v); i += c
      else if matches(a, "-stage1output") then
        val (v, c) = pullValue(a, "-stage1output"); cli = cli.copy(stage1OutputDir = v); i += c
      else if matches(a, "-testfloat3level") then
        val (v, c) = pullInt(a, "-testfloat3level"); cli = cli.copy(testfloat3Level = v); i += c
      else if matches(a, "-repeat") then
        val (v, c) = pullInt(a, "-repeat"); cli = cli.copy(repeat = v); i += c
      else if matches(a, "-march") then
        val (v, c) = pullValue(a, "-march"); cli = cli.copy(march = v); i += c
      else if matches(a, "-configs") then
        // DEC-5: silently ignore -configs since rvprobe has no toml dir.
        val (_, c) = pullValue(a, "-configs"); cli = cli.copy(configsIgnored = true); i += c
      else
        err += s"unknown flag: $a"
        i += 1

    if cli.stage1OutputDir.isEmpty then err += "-stage1output is required"
    if cli.testfloat3Level != 1 && cli.testfloat3Level != 2 then
      err += s"-testfloat3level must be 1 or 2, got ${cli.testfloat3Level}"
    if cli.repeat <= 0 then err += s"-repeat must be > 0"
    val errList = err.result()
    if errList.nonEmpty then Left(errList.mkString("; ")) else Right(cli)

  /** Filter the registry by march + pattern + integer-only. Matches the
   *  upstream `main.go` integer-mode filter exactly: drops `vf*` and
   *  `vmf*` EXCEPT `vfirst*` (which is an integer instruction despite
   *  its name prefix). Codex round-7 caught this exception.
   */
  def selectInsns(
    registry:    List[RvvInsn],
    cli:         Cli
  ): List[RvvInsn] =
    val patternRe = cli.pattern.r
    val marchExts = parseMarchExtensions(cli.march)
    registry.filter { insn =>
      val matchesPattern = patternRe.findFirstIn(insn.name).isDefined
      val inMarch        = marchExts.contains(insn.extension)
      val notFpFiltered  =
        if cli.integerOnly then
          // Mirror upstream main.go:142:
          //   if (vf* or vmf*) && !vfirst* { skip }
          val looksFp = insn.name.startsWith("vf") || insn.name.startsWith("vmf")
          val isVfirst = insn.name.startsWith("vfirst")
          !looksFp || isVfirst
        else true
      matchesPattern && inMarch && notFpFiltered
    }

  /** Parse the upstream-style march string into the set of enabled
   *  extension directory names. Mirrors `main.go`'s parse_extension.
   */
  def parseMarchExtensions(march: String): Set[String] =
    val base   = march.split("_", 2)(0).stripPrefix("rv32").stripPrefix("rv64")
    val hasV   = base.contains("v")
    val valid  = Set(
      "zvbb", "zvbc", "zfh", "zvfh", "zvfhmin", "zvkg", "zvkned",
      "zvknha", "zvksed", "zvksh", "zvfbfmin", "zvfbfwma")
    val parts  = march.split("_").toSet
    val zvfh   = parts.contains("zvfh")
    val zvfhmin = parts.contains("zvfhmin") || zvfh // zvfh implies zvfhmin
    val exts   = scala.collection.mutable.Set.empty[String]
    if hasV then exts += "v"
    for v <- valid do
      if parts.contains(v) then exts += v
    if zvfh then exts += "zvfh"
    if zvfhmin then exts += "zvfhmin"
    exts.toSet

  /** Emit a sorted Makefrag matching the upstream format:
   *  `tests = \\\n  <name> \\\n  ...`
   */
  def renderMakefrag(stageNames: List[String]): String =
    val sorted = stageNames.distinct.sorted
    val sb     = new StringBuilder
    sb.append("tests = \\\n")
    sorted.foreach(n => sb.append(s"  $n \\\n"))
    sb.toString

  /** Emit one stage-1 `.S` file for a single insn. Real per-schema
   *  emission for the integer `vd,vs2,vs1,vm` family covers the POC
   *  (vadd.vv, vwadd.vv, vmseq.vv, vnclip.wv); other schemas fall back
   *  to a structural placeholder with a TODO marker so they remain
   *  visible in the stage1 output but don't ship false-positive
   *  signatures.
   *
   *  File naming: `<insn>_<ext>-0.S` (upstream dot-to-underscore +
   *  split suffix).
   */
  def emitOne(insn: RvvInsn, cli: Cli, outDir: Path): String =
    val flatName = insn.name.replace('.', '_')
    val baseName = s"${flatName}_${insn.extension}-0"
    val fileName = s"$baseName.S"

    val envMacro = TestSEmit.envMacro(cli.xlen, hasFullV(cli.march))
    val content  = emitContent(insn, cli, envMacro)
    Files.createDirectories(outDir)
    Files.write(outDir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8))
    baseName

  /** Render the `.S` body for an insn. Dispatches per schema. The
   *  POC-relevant schemas use real assembly; the rest emit a
   *  structural placeholder so AC-16 / fan-out can distinguish
   *  "POC-ready" from "fan-out-pending" instructions.
   */
  private def emitContent(insn: RvvInsn, cli: Cli, envMacro: String): String =
    insn.schema match
      case Schema.VdVs2Vs1Vm                => emitVdVs2Vs1Vm(insn, cli, envMacro)
      case Schema.VdRs1mVm                  => emitUnitStrideLoad(insn, cli, envMacro)
      case Schema.Vs3Rs1mVm                 => emitUnitStrideStore(insn, cli, envMacro)
      case _                                => emitStructuralPlaceholder(insn, cli, envMacro)

  /** Real emission for the dominant integer 4-vector format. Covers
   *  vadd.vv, vsub.vv, vmul.vv, vwadd.vv, vmseq.vv, vnclip.wv, etc.
   *
   *  Strategy:
   *  - Per-LMUL sweep: M1, M2, M4 (not M8 to keep .S compact)
   *  - Per-SEW sweep at one canonical LMUL: Sew8, Sew16, Sew32, Sew64
   *  - For each combo: vsetvli, load vs1/vs2 test data via vle*.v,
   *    execute the instruction, emit magic word.
   *  - vd is v8, vs2 is v16, vs1 is v24 (matches upstream convention).
   *  - Test data is two interleaved vectors lowered from a small
   *    canonical predicate set (Zero, MaxSigned, One, AllOnes).
   */
  private def emitVdVs2Vs1Vm(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sews  = List(Sew.Sew8, Sew.Sew16, Sew.Sew32, Sew.Sew64).filter(_.bits <= cli.xlen)
    val lmuls = List(Lmul.M1, Lmul.M2, Lmul.M4)

    // Canonical 4-element witness per operand, lowered via ElemValueLowering.
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(Sew.Sew8),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(Sew.Sew8))

    val blocks    = List.newBuilder[TestSEmit.TestBlock]
    val allTestData = collection.mutable.ArrayBuffer.empty[Byte]
    val labels      = List.newBuilder[(String, Int)]

    for (sew, sewIdx) <- sews.zipWithIndex
        (lmul, lmulIdx) <- lmuls.zipWithIndex
    do
      val envRes = VTypeEnvelope(VType(sew, lmul, Vta.Agnostic, Vma.Agnostic),
                                  vl = 4, vlen = cli.vlen, xlen = cli.xlen)
      envRes.foreach { env =>
        val vs1Vec = ElemValueLowering.buildVector(canonical, sew, env.maxElements min 4)
        val vs2Vec = ElemValueLowering.buildVector(canonical.reverse, sew, env.maxElements min 4)
        val vs1Bytes = vec2bytes(vs1Vec, sew)
        val vs2Bytes = vec2bytes(vs2Vec, sew)
        val vs1Label = s"vs1_data_s${sew.bits}_l${lmul.toString}"
        val vs2Label = s"vs2_data_s${sew.bits}_l${lmul.toString}"
        labels += (vs1Label -> allTestData.size)
        allTestData ++= vs1Bytes
        labels += (vs2Label -> allTestData.size)
        allTestData ++= vs2Bytes
        // The actual setup + instruction sequence
        val setup = List(
          s"la a1, $vs1Label",
          s"vle${sew.bits}.v v24, (a1)",
          s"la a1, $vs2Label",
          s"vle${sew.bits}.v v16, (a1)")
        val insnAsm = formatInsnAsm(insn)

        // Compute result-EEW and result-EMUL whole-register count.
        // Round-9 Codex fix: widening (vwadd.vv at LMUL=4) has dest
        // EMUL=8, NOT base LMUL=4. Mask-producing (vmseq.vv) has
        // resultEEW=1 and resultEMUL=1 register.
        val (resultEew, resultWR) =
          if insn.widthProfile.maskDest then (1, 1)
          else
            val vdScale = insn.widthProfile.scaleOf(OperandRole.Vd)
            val (rewN, rewD) = (vdScale.numerator, vdScale.denominator)
            val rEew = sew.bits * rewN / rewD
            val baseWR = MagicInstrEmit.wholeRegisterCount(lmul)
            // resultWR = max(baseWR × scale, 1)
            val rWR    = math.max(baseWR * rewN / rewD, 1)
            (rEew, rWR.min(8))

        // Skip combinations where the result EEW exceeds XLEN (=ELEN):
        // upstream's gen-time filter excludes these too (insn_g.go's
        // VLEN/SEW/LMUL combination table), and vsetvli cannot encode
        // SEW>64. mask-dest (resultEew=1) and non-widening
        // (resultEew=sew.bits) always survive.
        val resultEewLegal = resultEew == 1 || resultEew <= cli.xlen
        if resultEewLegal then
          blocks += TestSEmit.TestBlock(
            envelope             = env,
            vectorGroup          = 8,
            vxsat                = insn.vxsat,
            insnAsm              = insnAsm,
            setupAsm             = setup,
            dataLabel            = None,
            resultEew            = resultEew,
            resultGroup          = 8,
            resultWholeRegisters = resultWR)
      }

    renderWithLabels(insn.name, envMacro, blocks.result(), allTestData.toVector, labels.result())

  /** Unit-stride load (vle32.v etc): vd <- mem[rs1]. POC: vle32.v. */
  private def emitUnitStrideLoad(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Agnostic, Vma.Agnostic),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    val data       = vec2bytes(ElemValueLowering.buildVector(canonical, sew, 4), sew)
    val labels     = List("ld_data_0" -> 0)
    val setup      = List("la a1, ld_data_0")
    val insnAsm    = s"${insn.name} v8, (a1)"
    val block      = TestSEmit.TestBlock(env, 8, false, insnAsm, setup, None)
    renderWithLabels(insn.name, envMacro, List(block), data.toVector, labels)

  /** Unit-stride store (vse32.v): mem[rs1] <- vs3. POC: vse32.v. */
  private def emitUnitStrideStore(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Agnostic, Vma.Agnostic),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    val srcData    = vec2bytes(ElemValueLowering.buildVector(canonical, sew, 4), sew)
    val labels     = List("st_src_0" -> 0)
    val setup      = List(
      "la a1, st_src_0",
      s"vle${sew.bits}.v v8, (a1)",
      "la a1, st_dst_0")
    val insnAsm    = s"${insn.name} v8, (a1)"
    val block      = TestSEmit.TestBlock(env, 8, false, insnAsm, setup, None)
    renderWithLabels(
      insn.name, envMacro, List(block), srcData.toVector,
      labels :+ ("st_dst_0" -> srcData.size))

  /** Structural placeholder for schemas not yet wired with real
   *  emission (strided / indexed / segmented / FP / vsetvl-as-insn).
   *  Marked with a TODO comment so AC-16 / Codex review can tell
   *  "POC-ready" from "fan-out-pending".
   */
  private def emitStructuralPlaceholder(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val env = VTypeEnvelope.unsafe(
      VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val block = TestSEmit.TestBlock(
      envelope    = env,
      vectorGroup = 8,
      vxsat       = false,
      insnAsm     = s"# TODO real emission for schema ${insn.schema} (${insn.name})",
      setupAsm    = Nil,
      dataLabel   = None)
    TestSEmit.render(insn.name, envMacro, List(block), Vector.empty, 64)

  /** Wrap TestSEmit.render with embedded labels so the test data
   *  section carries the labels each block needs.
   */
  private def renderWithLabels(
    name:     String,
    envMacro: String,
    blocks:   List[TestSEmit.TestBlock],
    data:     Vector[Byte],
    labels:   List[(String, Int)]
  ): String =
    val core    = TestSEmit.render(name, envMacro, blocks, data, 256)
    // Inject the labels into the testdata section after `testdata:`.
    val labelLines =
      if labels.isEmpty then ""
      else labels.map { case (lbl, off) => s"$lbl = testdata + $off" }.mkString("\n", "\n", "")
    core.replace("testdata:", "testdata:" + labelLines)

  /** Format the assembly mnemonic for an insn given its schema. The
   *  upstream operand convention puts vd in v8, vs2 in v16, vs1 in v24.
   */
  private def formatInsnAsm(insn: RvvInsn): String =
    val n = insn.name
    insn.schema match
      case Schema.VdVs2Vs1Vm => s"$n v8, v16, v24"
      case Schema.VdVs2Rs1Vm => s"$n v8, v16, a2"
      case Schema.VdVs2ImmVm => s"$n v8, v16, 0"
      case _                 => s"# unsupported schema for asm format: ${insn.schema}"

  /** Lower a BigInt-vector into a little-endian byte sequence sized
   *  per SEW. Used by emitVdVs2Vs1Vm and load/store emitters to fill
   *  the `testdata` section.
   */
  private def vec2bytes(vec: Vector[BigInt], sew: Sew): Array[Byte] =
    val bytesPerElem = sew.bits / 8
    val out          = new Array[Byte](vec.size * bytesPerElem)
    for (v, i) <- vec.zipWithIndex do
      val masked = v & ((BigInt(1) << sew.bits) - 1)
      for b <- 0 until bytesPerElem do
        out(i * bytesPerElem + b) = ((masked >> (b * 8)) & BigInt(0xff)).toByte
    out

  /** Parse the SEW field embedded in load/store names (vle32.v -> 32). */
  private def inferSewFromName(name: String): Option[Sew] =
    """v[ls]e?(\d+)""".r.findFirstMatchIn(name).map(_.group(1).toInt).flatMap {
      case 8  => Some(Sew.Sew8)
      case 16 => Some(Sew.Sew16)
      case 32 => Some(Sew.Sew32)
      case 64 => Some(Sew.Sew64)
      case _  => None
    }

  private def hasFullV(march: String): Boolean =
    val base = march.split("_", 2)(0).stripPrefix("rv32").stripPrefix("rv64")
    base.contains("v")

  /** End-to-end emission: select insns from registry, write per-insn
   *  `.S` files, write sorted Makefrag. Returns
   *  (stageNames, makefragContent).
   */
  def run(cli: Cli, registry: List[RvvInsn]): (List[String], String) =
    val out      = Paths.get(cli.stage1OutputDir)
    val selected = selectInsns(registry, cli)
    val names    = selected.map(insn => emitOne(insn, cli, out))
    val makefrag = renderMakefrag(names)
    (names, makefrag)

  /** @main entry-point: drop-in replacement for upstream main.go. */
  @main def driverMain(args: String*): Unit =
    parseCli(args.toArray) match
      case Left(msg) =>
        Console.err.println(s"rvprobe driver: $msg")
        sys.exit(1)
      case Right(cli) =>
        val (names, makefrag) = run(cli, RvvInsnRegistry.all)
        // Write Makefrag at cwd (matches upstream main.go behavior).
        Files.write(Paths.get("Makefrag"), makefrag.getBytes(StandardCharsets.UTF_8))
        println(s"rvprobe driver: wrote ${names.size} stage1 .S files + Makefrag")

/** Central registry of RvvInsn declarations, aggregated from the
 *  auto-generated per-extension lists under `insns/`. The generator
 *  (`audit/InsnsGenerator.regenerateInsns`) populates this; the
 *  registry size should match the upstream toml corpus (676).
 */
object RvvInsnRegistry:
  val all: List[RvvInsn] = me.jiuyang.rvprobe.rvv.insns.AllInsns.all
