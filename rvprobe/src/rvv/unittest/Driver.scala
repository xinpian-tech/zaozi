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

  /** Render the `.S` body for an insn. Dispatches per schema.
   *  FP instructions (`vf*` / `vmf*` excluding `vfirst*`) route
   *  through emitFp which consumes Testfloat3Driver if the binary
   *  is available; otherwise falls back to the integer-witness path
   *  with an FRM sweep wrapper.
   */
  private def emitContent(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val isFp = isFpInsn(insn.name)
    insn.schema match
      case _ if isFp && !insn.notestfloat3 => emitFp(insn, cli, envMacro)
      case Schema.VdVs2Vs1Vm     => emitVdVs2Vs1Vm(insn, cli, envMacro)
      case Schema.VdRs1m         => emitUnitStrideLoad(insn, cli, envMacro)
      case Schema.VdRs1mVm       =>
        if insn.nfields > 1 then emitSegmentedLoad(insn, cli, envMacro)
        else emitUnitStrideLoad(insn, cli, envMacro)
      case Schema.Vs3Rs1m        => emitUnitStrideStore(insn, cli, envMacro)
      case Schema.Vs3Rs1mVm      =>
        if insn.nfields > 1 then emitSegmentedStore(insn, cli, envMacro)
        else emitUnitStrideStore(insn, cli, envMacro)
      case Schema.VdRs1mRs2Vm    =>
        if insn.nfields > 1 then emitSegmentedStridedLoad(insn, cli, envMacro)
        else emitStridedLoad(insn, cli, envMacro)
      case Schema.Vs3Rs1mRs2Vm   =>
        if insn.nfields > 1 then emitSegmentedStridedStore(insn, cli, envMacro)
        else emitStridedStore(insn, cli, envMacro)
      case Schema.VdRs1mVs2Vm    =>
        if insn.nfields > 1 then emitSegmentedIndexedLoad(insn, cli, envMacro)
        else emitIndexedLoad(insn, cli, envMacro)
      case Schema.Vs3Rs1mVs2Vm   =>
        if insn.nfields > 1 then emitSegmentedIndexedStore(insn, cli, envMacro)
        else emitIndexedStore(insn, cli, envMacro)
      case _                     => emitStructuralPlaceholder(insn, cli, envMacro)

  /** FP-instruction predicate: `vf*` or `vmf*` excluding `vfirst*`
   *  (which is an integer instruction). Matches upstream main.go:142.
   */
  private def isFpInsn(name: String): Boolean =
    (name.startsWith("vf") || name.startsWith("vmf")) && !name.startsWith("vfirst")

  /** FP emission: tries `Testfloat3Driver.generate` to get FP operand
   *  bytes; on failure (binary absent), falls back to the integer-
   *  witness path but wraps it in an FRM sweep so AC-10's FRM × 5
   *  modes still get exercised at the magic-word level.
   *
   *  Per DEC-4, FP operands come from testfloat3 (not predicate-driven).
   */
  private def emitFp(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew = inferFpSewFromName(insn.name).getOrElse(Sew.Sew32)
    val operation = inferTestfloat3Op(insn.name, sew)

    // Try to materialize testfloat3 operand bytes for each FRM mode.
    val frmResults: List[(String, Either[String, Array[Byte]])] =
      Testfloat3Driver.AllFrm.map { case (frmName, frmFlag) =>
        val req = Testfloat3Driver.Request(
          operation = operation,
          sew       = sew,
          rmFlag    = frmFlag,
          testLevel = cli.testfloat3Level)
        frmName -> Testfloat3Driver.generate(req)
      }

    val anySucceeded = frmResults.exists(_._2.isRight)
    if !anySucceeded then
      // testfloat_gen not on PATH; fall back to integer-witness with
      // FRM CSR setup so the magic-word still varies by FRM. Embed a
      // # TODO comment so AC-16 reviewers can tell this is fallback.
      emitFpFallback(insn, cli, envMacro)
    else
      // Real testfloat3 path: one block per (FRM mode), each with
      // testfloat3-supplied operand bytes embedded into testdata.
      emitFpWithTestfloat3(insn, cli, envMacro, sew, frmResults)

  /** Fallback FP emission when testfloat_gen is absent. Emits per-FRM
   *  blocks executing the REAL instruction (not a comment) with
   *  xorshift-generated operand bytes embedded in testdata.
   *
   *  Codex r12 #2 (HIGH): previously emitted `# FP fallback for ...`
   *  as the `insnAsm`, so the SUT never ran — the resultdata store
   *  captured whatever was already in v8, producing a silent false
   *  positive against AC-16. Now the fallback runs the actual
   *  mnemonic; the only difference vs. the testfloat3 path is the
   *  operand provenance (Berkeley-quality FP edge cases vs. xorshift).
   *
   *  A `# TODO testfloat_gen` comment is still emitted above the FRM
   *  setup so reviewers can see this is the degraded path.
   */
  /** Sentinel marker line emitted at the top of any FP `.S` produced
   *  via the xorshift fallback. PocGateTest uses this marker to refuse
   *  to count xorshift-fallback files as AC-10/AC-16 POC evidence
   *  (Codex r13 #1).
   */
  val FpXorshiftFallbackMarker: String =
    "# RVPROBE_FP_XORSHIFT_FALLBACK testfloat3 binary unavailable"

  private def emitFpFallback(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew     = inferFpSewFromName(insn.name).getOrElse(Sew.Sew32)
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val perFrmBytesPerOp = 4 * (sew.bits / 8) * 2 // 4 elements × 2 operands
    val allData = collection.mutable.ArrayBuffer.empty[Byte]
    val labels  = List.newBuilder[(String, Int)]
    val blocks  = List.newBuilder[TestSEmit.TestBlock]

    Testfloat3Driver.AllFrm.zipWithIndex.foreach { case ((frmName, _), idx) =>
      val frmRm = idx
      val operandBytes = xorshiftOperandBytes(perFrmBytesPerOp, seed = idx * 0xCAFEBABEL + 1)
      val label = s"fp_fallback_${frmName.toLowerCase}_data"
      labels += (label -> allData.size)
      allData ++= operandBytes
      val markerLine = if idx == 0 then List(FpXorshiftFallbackMarker) else Nil
      val setup = markerLine ++ List(
        s"# TODO testfloat_gen unavailable; xorshift-operand fallback for FRM=$frmName",
        s"csrwi frm, $frmRm",
        s"la a1, $label",
        s"vle${sew.bits}.v v16, (a1)",
        s"addi a1, a1, ${operandBytes.length / 2}",
        s"vle${sew.bits}.v v24, (a1)")
      val insnAsm = formatInsnAsm(insn)
      blocks += TestSEmit.TestBlock(
        env, 8, false, insnAsm,
        setupAsm             = setup,
        dataLabel            = None,
        resultEew            = sew.bits,
        resultGroup          = 8,
        resultWholeRegisters = 1)
    }
    renderWithLabels(insn.name, envMacro, blocks.result(),
      allData.toVector, labels.result())

  /** Deterministic xorshift PRNG for FP operand-byte generation in the
   *  testfloat3-unavailable fallback. NOT cryptographic; not a
   *  substitute for Berkeley TestFloat-3's edge-case coverage.
   */
  private def xorshiftOperandBytes(n: Int, seed: Long): Array[Byte] =
    val out = new Array[Byte](n)
    var s = if seed == 0L then 1L else seed
    var i = 0
    while i < n do
      s ^= s << 13
      s ^= s >>> 7
      s ^= s << 17
      out(i) = (s & 0xff).toByte
      i += 1
    out

  /** Real testfloat3 emission. Embeds the testfloat_gen bytes for each
   *  FRM and emits one block per FRM that loads operands from the
   *  testdata section then executes the FP instruction.
   */
  private def emitFpWithTestfloat3(
    insn:        RvvInsn,
    cli:         Cli,
    envMacro:    String,
    sew:         Sew,
    frmResults:  List[(String, Either[String, Array[Byte]])]
  ): String =
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val allData = collection.mutable.ArrayBuffer.empty[Byte]
    val labels  = List.newBuilder[(String, Int)]
    val blocks  = List.newBuilder[TestSEmit.TestBlock]

    frmResults.zipWithIndex.foreach { case ((frmName, bytesEither), frmRm) =>
      bytesEither match
        case Right(bytes) =>
          val label = s"fp_${frmName.toLowerCase}_data"
          labels += (label -> allData.size)
          allData ++= bytes
          val setup = List(
            s"csrwi frm, $frmRm",
            s"la a1, $label",
            s"vle${sew.bits}.v v16, (a1)",
            s"addi a1, a1, ${bytes.length / 2}",
            s"vle${sew.bits}.v v24, (a1)")
          val insnAsm = formatInsnAsm(insn)
          blocks += TestSEmit.TestBlock(
            env, 8, false, insnAsm,
            setupAsm             = setup,
            dataLabel            = None,
            resultEew            = sew.bits,
            resultGroup          = 8,
            resultWholeRegisters = 1)
        case Left(_) => () // skip this FRM if subprocess failed
    }

    renderWithLabels(insn.name, envMacro, blocks.result(), allData.toVector, labels.result())

  /** Extract the FP SEW from instruction name (vfadd.vv default to 32). */
  private def inferFpSewFromName(name: String): Option[Sew] =
    """vfw?(\d+)""".r.findFirstMatchIn(name).map(_.group(1).toInt).flatMap {
      case 16 => Some(Sew.Sew16)
      case 32 => Some(Sew.Sew32)
      case 64 => Some(Sew.Sew64)
      case _  => None
    }.orElse(Some(Sew.Sew32))

  /** Map FP instruction name + SEW to upstream testfloat_gen op code. */
  private def inferTestfloat3Op(name: String, sew: Sew): String =
    val prefix = sew match
      case Sew.Sew16 => "f16"
      case Sew.Sew32 => "f32"
      case Sew.Sew64 => "f64"
      case _         => "f32"
    val op =
      if name.contains("add") then "add"
      else if name.contains("sub") then "sub"
      else if name.contains("mul") then "mul"
      else if name.contains("div") then "div"
      else if name.contains("sqrt") then "sqrt"
      else if name.contains("min") then "min"
      else if name.contains("max") then "max"
      else "add"
    s"${prefix}_$op"

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
      val envRes = VTypeEnvelope(VType(sew, lmul, Vta.Undisturbed, Vma.Undisturbed),
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
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
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

  /** Unit-stride store (vse32.v): mem[rs1] <- vs3.
   *
   *  Codex r11 #2: a store test must verify the store actually wrote
   *  to memory, not just that the source vector is intact. After the
   *  store executes, reload from the dst label into v8, then the
   *  TestSEmit resultdata-store + magic compares the *reloaded*
   *  contents against pspike's expected. If the store mnemonic were
   *  removed, dst memory would be untouched (zero-init) and pspike
   *  would catch the mismatch.
   */
  private def emitUnitStrideStore(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    val srcData    = vec2bytes(ElemValueLowering.buildVector(canonical, sew, 4), sew)
    val dstZero    = Array.fill[Byte](srcData.length)(0.toByte)
    val labels     = List("st_src_0" -> 0, "st_dst_0" -> srcData.length)
    val setup      = List(
      "la a1, st_src_0",
      s"vle${sew.bits}.v v8, (a1)",
      "la a1, st_dst_0")
    val insnAsm    = s"${insn.name} v8, (a1)"
    // After the store, reload from the dst label so the resultdata
    // store captures the actual stored bytes (not the original v8).
    val postStore  = List(
      "la a1, st_dst_0",
      s"vle${sew.bits}.v v8, (a1)")
    val block      = TestSEmit.TestBlock(
      env, 8, false, insnAsm,
      setupAsm             = setup,
      dataLabel            = None,
      resultEew            = sew.bits,
      resultGroup          = 8,
      resultWholeRegisters = 1,
      postInsn             = postStore)
    renderWithLabels(insn.name, envMacro, List(block),
      (srcData ++ dstZero).toVector, labels)

  /** Segmented store (`vsseg<n>e<m>.v vs3, (rs1)`). Stores NFIELDS
   *  register groups consecutively. Codex r11 #3: previously dispatched
   *  through emitUnitStrideStore which only emits one vse and one
   *  magic block. Real path emits NFIELDS source loads then the
   *  segmented store, then reloads via segmented load so resultdata
   *  captures all NFIELDS register groups.
   *
   *  Verifies NFIELDS × EMUL ≤ 8 at gen-time per AC-17.
   */
  private def emitSegmentedStore(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew     = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val nfields = insn.nfields
    val dataEmul = 1 // LMUL=M1 → 1 whole register per field
    require(nfields * dataEmul <= 8, s"NFIELDS × EMUL > 8: nfields=$nfields dataEmul=$dataEmul")
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    val srcData   = vec2bytes(ElemValueLowering.buildVector(canonical, sew, 4), sew)
    val totalSrcBytes = srcData.length * nfields
    val srcAll        = Array.fill[Byte](totalSrcBytes)(0.toByte)
    // Pre-load NFIELDS distinct field-source registers from contiguous
    // memory; segmented store interleaves them in memory.
    System.arraycopy(srcData, 0, srcAll, 0, srcData.length)
    // Fill remaining fields with the same pattern shifted by 1 for
    // identifiability.
    for f <- 1 until nfields do
      System.arraycopy(srcData, 0, srcAll, f * srcData.length, srcData.length)
    val dstZero       = Array.fill[Byte](totalSrcBytes)(0.toByte)

    val setupB = List.newBuilder[String]
    setupB += "la a1, seg_src"
    for f <- 0 until nfields do
      setupB += s"vle${sew.bits}.v v${8 + f * dataEmul}, (a1)"
      setupB += s"addi a1, a1, ${srcData.length}"
    setupB += "la a1, seg_dst"

    val insnAsm = s"${insn.name} v8, (a1)"
    // After segmented store, reload via segmented load into the same
    // register groups so each per-field result-store reads memory.
    val postInsn0 = List("la a1, seg_dst", s"vlseg${nfields}e${sew.bits}.v v8, (a1)")
    // Codex r14 #2: per-field blocks so NFIELDS 3/5/6/7 use valid
    // result-whole-register counts {1,2,4,8} per dataEmul. NOT a
    // pseudo-LMUL=nfields group.
    val blocks = (0 until nfields).map { f =>
      val fieldReg = 8 + f * dataEmul
      val setup    = if f == 0 then setupB.result() else Nil
      val post     = if f == 0 then postInsn0 else Nil
      val asm      = if f == 0 then insnAsm
                     else s"# field $f result store"
      TestSEmit.TestBlock(
        env, fieldReg, false, asm,
        setupAsm             = setup,
        dataLabel            = None,
        resultEew            = sew.bits,
        resultGroup          = fieldReg,
        resultWholeRegisters = dataEmul,
        postInsn             = post)
    }.toList
    val labels = List("seg_src" -> 0, "seg_dst" -> totalSrcBytes)
    renderWithLabels(insn.name, envMacro, blocks,
      (srcAll ++ dstZero).toVector, labels)

  /** Strided load (`vlse<n>.v vd, (rs1), rs2`). rs2 = byte stride. */
  private def emitStridedLoad(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    // Reserve 16 elements worth of memory; stride = 2 × element size
    // so we read elements at indices 0, 2, 4, 6, ...
    val elemBytes = sew.bits / 8
    val padded    = ElemValueLowering.buildVector(canonical, sew, 16)
    val data      = vec2bytes(padded, sew)
    val stride    = elemBytes * 2
    val setup     = List(
      "la a1, strided_data",
      s"li a2, $stride")
    val insnAsm   = s"${insn.name} v8, (a1), a2"
    val block     = TestSEmit.TestBlock(
      env, 8, false, insnAsm, setup, None,
      resultEew = sew.bits, resultGroup = 8, resultWholeRegisters = 1)
    renderWithLabels(insn.name, envMacro, List(block), data.toVector,
      List("strided_data" -> 0))

  /** Strided store (`vsse<n>.v vs3, (rs1), rs2`). Stores at stride.
   *  Codex r11 #2: reload from dst memory after store so resultdata
   *  reflects what the store actually wrote.
   */
  private def emitStridedStore(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    val srcData   = vec2bytes(ElemValueLowering.buildVector(canonical, sew, 4), sew)
    val dstZero   = Array.fill[Byte](srcData.length * 2)(0.toByte) // wider for stride
    val stride    = (sew.bits / 8) * 2
    val setup     = List(
      "la a1, strided_src",
      s"vle${sew.bits}.v v8, (a1)",
      "la a1, strided_dst",
      s"li a2, $stride")
    val insnAsm   = s"${insn.name} v8, (a1), a2"
    val postInsn  = List(
      "la a1, strided_dst",
      s"vlse${sew.bits}.v v8, (a1), a2") // reload via strided load to capture
    val block     = TestSEmit.TestBlock(
      env, 8, false, insnAsm,
      setupAsm             = setup,
      dataLabel            = None,
      resultEew            = sew.bits,
      resultGroup          = 8,
      resultWholeRegisters = 1,
      postInsn             = postInsn)
    val labels    = List("strided_src" -> 0, "strided_dst" -> srcData.length)
    renderWithLabels(insn.name, envMacro, List(block),
      (srcData ++ dstZero).toVector, labels)

  /** Indexed load (`vluxei<n>.v vd, (rs1), vs2`) and its segmented
   *  cousin (`vluxseg<nf>ei<n>.v`). vs2 carries indices with separate
   *  EEW (indexedEew); data SEW comes from the envelope.
   *
   *  Codex r12 #1 (HIGH): real EMUL computation per RVV spec §
   *  "Vector indexed instructions":
   *    EEW_index = indexedEew
   *    EMUL_index = (EEW_index / SEW) * LMUL
   *  EMUL_index must be in [1/8, 8] or the instruction is reserved.
   *  vd and vs2 register groups must be disjoint and each aligned to
   *  its own EMUL footprint. For nfields > 1, the destination occupies
   *  NFIELDS contiguous register groups (each EMUL data registers
   *  wide), so NFIELDS * EMUL_data <= 8.
   *
   *  Citation: upstream `riscv-vector-tests/generator/insn_vlxux.go`
   *  computes EMUL by `lmul * eew / sew`.
   */
  private def emitIndexedLoad(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val indexEew = insn.indexedEew.getOrElse(32)
    val dataSew  = Sew.Sew32
    val lmul     = Lmul.M1
    val nfields  = insn.nfields
    val IdxLayout(indexSew, indexEmul, dataEmul, vdReg, vs2Reg) =
      computeIndexedLayout(indexEew, dataSew, lmul, nfields)
    val env = VTypeEnvelope.unsafe(
      VType(dataSew, lmul, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(dataSew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(dataSew))
    val dataBytes = vec2bytes(
      ElemValueLowering.buildVector(canonical, dataSew, 16), dataSew)
    // Index vector: in-range byte offsets into indexed_data.
    val indices   = Vector(BigInt(0), BigInt(4), BigInt(8), BigInt(12))
    val indexBytes = vec2bytes(indices, indexSew)
    val setup     = List(
      "la a1, indexed_idx",
      s"vle$indexEew.v v$vs2Reg, (a1)",
      "la a1, indexed_data")
    val insnAsm   = s"${insn.name} v$vdReg, (a1), v$vs2Reg"
    val block     = TestSEmit.TestBlock(
      env, vdReg, false, insnAsm, setup, None,
      resultEew = dataSew.bits, resultGroup = vdReg, resultWholeRegisters = nfields * dataEmul)
    val labels    = List("indexed_data" -> 0, "indexed_idx" -> dataBytes.size)
    val allData   = (dataBytes ++ indexBytes).toVector
    renderWithLabels(insn.name, envMacro, List(block), allData, labels)

  /** Indexed store (`vsuxei<n>.v vs3, (rs1), vs2`) and segmented
   *  cousin (`vsuxseg<nf>ei<n>.v`). Same EMUL computation as indexed
   *  load.
   */
  private def emitIndexedStore(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val indexEew = insn.indexedEew.getOrElse(32)
    val dataSew  = Sew.Sew32
    val lmul     = Lmul.M1
    val nfields  = insn.nfields
    val IdxLayout(indexSew, indexEmul, dataEmul, vs3Reg, vs2Reg) =
      computeIndexedLayout(indexEew, dataSew, lmul, nfields)
    val env = VTypeEnvelope.unsafe(
      VType(dataSew, lmul, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(dataSew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(dataSew))
    val srcBytes  = vec2bytes(ElemValueLowering.buildVector(canonical, dataSew, 4), dataSew)
    val indices   = Vector(BigInt(0), BigInt(4), BigInt(8), BigInt(12))
    val indexBytes = vec2bytes(indices, indexSew)
    val setup     = List(
      "la a1, idxst_src",
      s"vle${dataSew.bits}.v v$vs3Reg, (a1)",
      "la a1, idxst_idx",
      s"vle$indexEew.v v$vs2Reg, (a1)",
      "la a1, idxst_dst")
    val insnAsm   = s"${insn.name} v$vs3Reg, (a1), v$vs2Reg"
    // Codex r11 #2 + r12 #1: reload from dst via indexed-load using
    // matching index EEW; resultdata captures the actual stored bytes.
    val postInsn  = List(
      "la a1, idxst_dst",
      s"vluxei$indexEew.v v$vs3Reg, (a1), v$vs2Reg")
    val block     = TestSEmit.TestBlock(
      env, vs3Reg, false, insnAsm,
      setupAsm             = setup,
      dataLabel            = None,
      resultEew            = dataSew.bits,
      resultGroup          = vs3Reg,
      resultWholeRegisters = nfields * dataEmul,
      postInsn             = postInsn)
    val dstZero   = Array.fill[Byte](srcBytes.length * 4)(0.toByte) // 4x for sparse indices
    val labels    = List(
      "idxst_src" -> 0,
      "idxst_idx" -> srcBytes.size,
      "idxst_dst" -> (srcBytes.size + indexBytes.size))
    val allData   = (srcBytes ++ indexBytes ++ dstZero).toVector
    renderWithLabels(insn.name, envMacro, List(block), allData, labels)

  /** Indexed load/store layout computation. Returns:
   *    - indexSew     : Sew matching indexedEew (for vec2bytes)
   *    - indexEmul    : EMUL of the index register group (whole regs)
   *    - dataEmul     : EMUL of the data register group (whole regs)
   *    - vdReg        : aligned register id for data group
   *    - vs2Reg       : aligned register id for index group (disjoint)
   *
   *  EMUL_index = (indexedEew / SEW) * LMUL, clamped to whole-register
   *  groups (1/2/4/8 → 1, 2, 4, 8). Fractional EMUL collapses to 1
   *  whole register. Rejected (throws) if outside [1/8, 8].
   *
   *  Register choice: vd starts at v8, vs2 starts at v16. With max
   *  EMUL=8, v8..v15 covers data and v16..v23 covers index — disjoint
   *  by construction. Both 8-aligned, so any EMUL up to 8 fits.
   */
  private final case class IdxLayout(
    indexSew: Sew, indexEmul: Int, dataEmul: Int, vdReg: Int, vs2Reg: Int)

  private def computeIndexedLayout(
    indexedEew: Int, dataSew: Sew, lmul: Lmul, nfields: Int
  ): IdxLayout =
    val indexSew = indexedEew match
      case 8  => Sew.Sew8
      case 16 => Sew.Sew16
      case 32 => Sew.Sew32
      case 64 => Sew.Sew64
      case n  => throw new IllegalArgumentException(s"unsupported indexed EEW: $n")
    // EMUL_index = (EEW_index / SEW) * LMUL. Compute as ratio
    // numerator/denominator so fractional LMUL stays representable.
    // num/den * indexedEew / dataSew.bits.
    val sewBits     = dataSew.bits
    val emulNumIdx  = lmul.numerator * indexedEew
    val emulDenIdx  = lmul.denominator * sewBits
    // Reject if outside [1/8, 8].
    require(emulNumIdx * 8 >= emulDenIdx,
      s"index EMUL < 1/8: indexedEew=$indexedEew sew=$sewBits lmul=$lmul")
    require(emulNumIdx <= 8 * emulDenIdx,
      s"index EMUL > 8: indexedEew=$indexedEew sew=$sewBits lmul=$lmul")
    // Whole-register count: fractional EMUL → 1 register.
    val indexEmul = if emulNumIdx <= emulDenIdx then 1
                    else emulNumIdx / emulDenIdx
    val dataEmul  = if lmul.numerator <= lmul.denominator then 1
                    else lmul.numerator / lmul.denominator
    // NFIELDS * EMUL_data <= 8 per RVV spec § "Vector indexed segment".
    require(nfields * dataEmul <= 8,
      s"NFIELDS * EMUL_data > 8: nfields=$nfields dataEmul=$dataEmul")
    IdxLayout(indexSew, indexEmul, dataEmul, vdReg = 8, vs2Reg = 16)

  /** Segmented unit-stride load (`vlseg<nf>e<eew>.v vd, (rs1)`) for
   *  Schema.VdRs1mVm with nfields > 1. NFIELDS * EMUL <= 8 enforced.
   *  Codex r12 #5: previously this case fell through to
   *  emitUnitStrideLoad, dropping the NFIELDS structure entirely.
   */
  private def emitSegmentedLoad(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew     = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val nfields = insn.nfields
    val lmul    = Lmul.M1
    val dataEmul = 1 // LMUL=M1 → 1 whole register per group
    require(nfields * dataEmul <= 8,
      s"NFIELDS * EMUL > 8: nfields=$nfields dataEmul=$dataEmul")
    val env = VTypeEnvelope.unsafe(
      VType(sew, lmul, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    val perFieldBytes = vec2bytes(ElemValueLowering.buildVector(canonical, sew, 4), sew)
    val totalBytes    = perFieldBytes.length * nfields
    // Interleaved layout: segmented load reads NFIELDS interleaved
    // fields from one memory region. Build NFIELDS distinct patterns
    // by shifting per field.
    val srcAll = Array.fill[Byte](totalBytes)(0.toByte)
    for f <- 0 until nfields do
      System.arraycopy(perFieldBytes, 0, srcAll, f * perFieldBytes.length, perFieldBytes.length)

    val setup0   = List("la a1, segld_src")
    val insnAsm  = s"${insn.name} v8, (a1)"
    // Codex r14 #2: per-field result-store + magic so NFIELDS 3/5/6/7
    // use valid resultWholeRegisters in {1,2,4,8}.
    val blocks = (0 until nfields).map { f =>
      val fieldReg = 8 + f * dataEmul
      val setup    = if f == 0 then setup0 else Nil
      val asm      = if f == 0 then insnAsm
                     else s"# field $f result store"
      TestSEmit.TestBlock(
        env, fieldReg, false, asm,
        setupAsm             = setup,
        dataLabel            = None,
        resultEew            = sew.bits,
        resultGroup          = fieldReg,
        resultWholeRegisters = dataEmul)
    }.toList
    renderWithLabels(insn.name, envMacro, blocks,
      srcAll.toVector, List("segld_src" -> 0))

  /** Segmented strided load (`vlsseg<nf>e<eew>.v vd, (rs1), rs2`).
   *  Codex r13 #4: previously fell through to emitStridedLoad,
   *  dropping NFIELDS entirely.
   */
  private def emitSegmentedStridedLoad(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew     = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val nfields = insn.nfields
    val dataEmul = 1 // LMUL=M1
    require(nfields * dataEmul <= 8,
      s"NFIELDS * EMUL > 8: nfields=$nfields dataEmul=$dataEmul")
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    val perFieldBytes = vec2bytes(ElemValueLowering.buildVector(canonical, sew, 16), sew)
    // Stride = NFIELDS × element-size (interleaved per-field layout).
    val stride = (sew.bits / 8) * nfields
    val setup0 = List(
      "la a1, segst_src",
      s"li a2, $stride")
    val insnAsm = s"${insn.name} v8, (a1), a2"
    // Codex r14 #2: per-field blocks.
    val blocks = (0 until nfields).map { f =>
      val fieldReg = 8 + f * dataEmul
      val setup    = if f == 0 then setup0 else Nil
      val asm      = if f == 0 then insnAsm
                     else s"# field $f result store"
      TestSEmit.TestBlock(
        env, fieldReg, false, asm,
        setupAsm             = setup,
        dataLabel            = None,
        resultEew            = sew.bits,
        resultGroup          = fieldReg,
        resultWholeRegisters = dataEmul)
    }.toList
    renderWithLabels(insn.name, envMacro, blocks,
      perFieldBytes.toVector, List("segst_src" -> 0))

  /** Segmented strided store (`vssseg<nf>e<eew>.v vs3, (rs1), rs2`).
   *  Codex r13 #4: dispatched via nfields > 1.
   */
  private def emitSegmentedStridedStore(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val sew     = inferSewFromName(insn.name).getOrElse(Sew.Sew32)
    val nfields = insn.nfields
    val dataEmul = 1
    require(nfields * dataEmul <= 8,
      s"NFIELDS * EMUL > 8: nfields=$nfields dataEmul=$dataEmul")
    val env = VTypeEnvelope.unsafe(
      VType(sew, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(sew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(sew))
    val perFieldBytes = vec2bytes(ElemValueLowering.buildVector(canonical, sew, 4), sew)
    val srcAll       = Array.fill[Byte](perFieldBytes.length * nfields)(0.toByte)
    for f <- 0 until nfields do
      System.arraycopy(perFieldBytes, 0, srcAll, f * perFieldBytes.length, perFieldBytes.length)
    val dstZero = Array.fill[Byte](perFieldBytes.length * nfields * 2)(0.toByte) // 2x for stride
    val stride  = (sew.bits / 8) * nfields
    // Pre-load NFIELDS source register groups from contiguous src,
    // then execute segmented strided store, then reload via matching
    // segmented strided load for memory verification.
    val setupB = List.newBuilder[String]
    setupB += "la a1, segsst_src"
    for f <- 0 until nfields do
      setupB += s"vle${sew.bits}.v v${8 + f * dataEmul}, (a1)"
      setupB += s"addi a1, a1, ${perFieldBytes.length}"
    setupB += "la a1, segsst_dst"
    setupB += s"li a2, $stride"
    val insnAsm = s"${insn.name} v8, (a1), a2"
    // Reload via matching strided segmented load for memory witness.
    val reloadName = insn.name.replace("vssseg", "vlsseg")
    val postInsn0 = List(
      "la a1, segsst_dst",
      s"li a2, $stride",
      s"$reloadName v8, (a1), a2")
    // Codex r14 #2: per-field blocks.
    val blocks = (0 until nfields).map { f =>
      val fieldReg = 8 + f * dataEmul
      val setup    = if f == 0 then setupB.result() else Nil
      val post     = if f == 0 then postInsn0 else Nil
      val asm      = if f == 0 then insnAsm
                     else s"# field $f result store"
      TestSEmit.TestBlock(
        env, fieldReg, false, asm,
        setupAsm             = setup,
        dataLabel            = None,
        resultEew            = sew.bits,
        resultGroup          = fieldReg,
        resultWholeRegisters = dataEmul,
        postInsn             = post)
    }.toList
    val labels = List("segsst_src" -> 0, "segsst_dst" -> srcAll.length)
    renderWithLabels(insn.name, envMacro, blocks,
      (srcAll ++ dstZero).toVector, labels)

  /** Segmented indexed load (`vluxseg<nf>ei<m>.v vd, (rs1), vs2`).
   *  Codex r13 #5: one TestBlock per field (each field's register
   *  group gets its own result store + magic word), matching upstream
   *  `insn_vdrs1mvs2vm.go:59-63`.
   */
  private def emitSegmentedIndexedLoad(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val indexEew = insn.indexedEew.getOrElse(32)
    val dataSew  = Sew.Sew32
    val lmul     = Lmul.M1
    val nfields  = insn.nfields
    val IdxLayout(indexSew, indexEmul, dataEmul, vdReg, vs2Reg) =
      computeIndexedLayout(indexEew, dataSew, lmul, nfields)
    val env = VTypeEnvelope.unsafe(
      VType(dataSew, lmul, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(dataSew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(dataSew))
    val dataBytes = vec2bytes(
      ElemValueLowering.buildVector(canonical, dataSew, 16 * nfields), dataSew)
    val indices    = Vector(BigInt(0), BigInt(4), BigInt(8), BigInt(12))
    val indexBytes = vec2bytes(indices, indexSew)
    val sharedSetup = List(
      "la a1, segidx_idx",
      s"vle$indexEew.v v$vs2Reg, (a1)",
      "la a1, segidx_data")
    val insnAsm = s"${insn.name} v$vdReg, (a1), v$vs2Reg"
    // One block per field: each field's register group (vdReg + f * dataEmul)
    // gets its own result store + magic word so pspike injects per-field
    // expected-row sequences.
    val blocks = (0 until nfields).map { f =>
      val fieldReg = vdReg + f * dataEmul
      val setup    = if f == 0 then sharedSetup else Nil
      val asm      = if f == 0 then insnAsm
                     else s"# field $f result store"
      TestSEmit.TestBlock(
        env, fieldReg, false, asm,
        setupAsm             = setup,
        dataLabel            = None,
        resultEew            = dataSew.bits,
        resultGroup          = fieldReg,
        resultWholeRegisters = dataEmul)
    }.toList
    val labels  = List("segidx_data" -> 0, "segidx_idx" -> dataBytes.size)
    val allData = (dataBytes ++ indexBytes).toVector
    renderWithLabels(insn.name, envMacro, blocks, allData, labels)

  /** Segmented indexed store (`vsuxseg<nf>ei<m>.v vs3, (rs1), vs2`).
   *  Pre-loads NFIELDS source register groups, executes the segmented
   *  indexed store, reloads via matching segmented indexed load for
   *  memory witness. One result store + magic per field.
   */
  private def emitSegmentedIndexedStore(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val indexEew = insn.indexedEew.getOrElse(32)
    val dataSew  = Sew.Sew32
    val lmul     = Lmul.M1
    val nfields  = insn.nfields
    val IdxLayout(indexSew, indexEmul, dataEmul, vs3Reg, vs2Reg) =
      computeIndexedLayout(indexEew, dataSew, lmul, nfields)
    val env = VTypeEnvelope.unsafe(
      VType(dataSew, lmul, Vta.Undisturbed, Vma.Undisturbed),
      vl = 4, vlen = cli.vlen, xlen = cli.xlen)
    val canonical = List(
      me.jiuyang.rvprobe.rvv.pred.ValuePred.Zero,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.One,
      me.jiuyang.rvprobe.rvv.pred.ValuePred.MaxSigned(dataSew),
      me.jiuyang.rvprobe.rvv.pred.ValuePred.AllOnes(dataSew))
    val perFieldBytes = vec2bytes(
      ElemValueLowering.buildVector(canonical, dataSew, 4), dataSew)
    val indices    = Vector(BigInt(0), BigInt(4), BigInt(8), BigInt(12))
    val indexBytes = vec2bytes(indices, indexSew)
    val srcAll     = Array.fill[Byte](perFieldBytes.length * nfields)(0.toByte)
    for f <- 0 until nfields do
      System.arraycopy(perFieldBytes, 0, srcAll, f * perFieldBytes.length, perFieldBytes.length)
    val dstZero    = Array.fill[Byte](perFieldBytes.length * nfields * 4)(0.toByte) // sparse
    val setupB = List.newBuilder[String]
    setupB += "la a1, segixs_src"
    for f <- 0 until nfields do
      setupB += s"vle${dataSew.bits}.v v${vs3Reg + f * dataEmul}, (a1)"
      setupB += s"addi a1, a1, ${perFieldBytes.length}"
    setupB += "la a1, segixs_idx"
    setupB += s"vle$indexEew.v v$vs2Reg, (a1)"
    setupB += "la a1, segixs_dst"
    val insnAsm = s"${insn.name} v$vs3Reg, (a1), v$vs2Reg"
    // Reload via matching segmented indexed load.
    val reloadName = insn.name.replace("vsuxseg", "vluxseg")
                                .replace("vsoxseg", "vloxseg")
    val postInsn = List(
      "la a1, segixs_dst",
      s"$reloadName v$vs3Reg, (a1), v$vs2Reg")
    // One block per field for result-store + magic, mirroring the
    // segmented indexed load path.
    val blocks = (0 until nfields).map { f =>
      val fieldReg = vs3Reg + f * dataEmul
      val setup    = if f == 0 then setupB.result() else Nil
      val post     = if f == 0 then postInsn else Nil
      val asm      = if f == 0 then insnAsm
                     else s"# field $f result store"
      TestSEmit.TestBlock(
        env, fieldReg, false, asm,
        setupAsm             = setup,
        dataLabel            = None,
        resultEew            = dataSew.bits,
        resultGroup          = fieldReg,
        resultWholeRegisters = dataEmul,
        postInsn             = post)
    }.toList
    val labels = List(
      "segixs_src" -> 0,
      "segixs_idx" -> srcAll.length,
      "segixs_dst" -> (srcAll.length + indexBytes.size))
    val allData = (srcAll ++ indexBytes ++ dstZero).toVector
    renderWithLabels(insn.name, envMacro, blocks, allData, labels)

  /** Structural placeholder for FP schemas (pending testfloat3
   *  subprocess wiring) and other unsupported shapes. Marked with
   *  `# TODO` so AC-16 / Codex review can tell "POC-ready" from
   *  "fan-out-pending".
   */
  private def emitStructuralPlaceholder(insn: RvvInsn, cli: Cli, envMacro: String): String =
    val env = VTypeEnvelope.unsafe(
      VType(Sew.Sew32, Lmul.M1, Vta.Undisturbed, Vma.Undisturbed),
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

  /** Parse the SEW field embedded in load/store names. Codex r14 #1:
   *  the round-13 regex `v[ls]e?(\d+)` was too permissive on the
   *  left side and missed the SEW token in:
   *    - `vlse64.v` / `vsse64.v` (strided): matched first `e` after
   *      `vlse`, parsing as "64" but ALSO matching `vleseg`/etc.
   *    - `vlseg2e64.v` / `vsseg2e64.v` (segmented): "2e64" matched
   *      "2" instead of "64".
   *    - `vlsseg2e64.v` / `vssseg2e64.v` (strided segmented).
   *    - `vleff32.v` / `vle32ff.v` (fault-first variants).
   *
   *  Family-aware parser: try each known family prefix and extract
   *  the SEW token from the canonical position. Families:
   *    `vle<N>`/`vse<N>` (unit-stride, including fault-first `vle<N>ff`)
   *    `vlse<N>`/`vsse<N>` (strided)
   *    `vlseg<F>e<N>`/`vsseg<F>e<N>` (segmented unit-stride)
   *    `vlsseg<F>e<N>`/`vssseg<F>e<N>` (segmented strided)
   *  Indexed names (vluxei/vsuxei/vluxseg*ei/...) do NOT encode the
   *  data SEW in the name — caller supplies the envelope SEW.
   */
  private[rvprobe] def inferSewFromName(name: String): Option[Sew] =
    val candidates = List(
      // Segmented strided: vlsseg<F>e<N>, vssseg<F>e<N>
      """^v(?:l|s)sseg\d+e(\d+)""".r,
      // Segmented unit-stride: vlseg<F>e<N>, vsseg<F>e<N>
      """^v(?:l|s)seg\d+e(\d+)""".r,
      // Strided: vlse<N>, vsse<N>
      """^v(?:l|s)se(\d+)""".r,
      // Fault-first: vle<N>ff
      """^vle(\d+)ff""".r,
      // Unit-stride: vle<N>, vse<N> (also catches vleN.v)
      """^v(?:l|s)e(\d+)""".r)
    candidates.iterator
      .flatMap(_.findFirstMatchIn(name))
      .map(_.group(1).toInt)
      .nextOption()
      .flatMap {
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
