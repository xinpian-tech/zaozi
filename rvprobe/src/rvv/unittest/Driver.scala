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

  /** Parse args in the upstream-compatible style: `-flag value`. */
  def parseCli(args: Array[String]): Either[String, Cli] =
    var cli = Cli()
    var i   = 0
    val n   = args.length
    val err = List.newBuilder[String]
    while i < n do
      val a = args(i)
      a match
        case "-VLEN"             => cli = cli.copy(vlen = nextInt(args, i)); i += 2
        case "-XLEN"             => cli = cli.copy(xlen = nextInt(args, i)); i += 2
        case "-split"            => cli = cli.copy(splitLines = nextInt(args, i)); i += 2
        case "-integer"          => cli = cli.copy(integerOnly = true); i += 1
        case "-pattern"          => cli = cli.copy(pattern = args(i + 1)); i += 2
        case "-stage1output"     => cli = cli.copy(stage1OutputDir = args(i + 1)); i += 2
        case "-testfloat3level"  => cli = cli.copy(testfloat3Level = nextInt(args, i)); i += 2
        case "-repeat"           => cli = cli.copy(repeat = nextInt(args, i)); i += 2
        case "-march"            => cli = cli.copy(march = args(i + 1)); i += 2
        case "-configs"          =>
          // DEC-5: silently ignore -configs since rvprobe has no toml dir.
          cli = cli.copy(configsIgnored = true)
          i += 2
        case other               =>
          err += s"unknown flag: $other"
          i += 1
    if cli.stage1OutputDir.isEmpty then err += "-stage1output is required"
    if cli.testfloat3Level != 1 && cli.testfloat3Level != 2 then
      err += s"-testfloat3level must be 1 or 2, got ${cli.testfloat3Level}"
    if cli.repeat <= 0 then err += s"-repeat must be > 0"
    val errList = err.result()
    if errList.nonEmpty then Left(errList.mkString("; ")) else Right(cli)

  private def nextInt(args: Array[String], i: Int): Int =
    if i + 1 >= args.length then throw new IllegalArgumentException(s"missing arg after ${args(i)}")
    args(i + 1).toInt

  /** Filter the registry by march + pattern + integer-only. */
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
        if cli.integerOnly then !insn.name.startsWith("vf") && !insn.name.startsWith("vmf")
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

  /** Emit one stage-1 `.S` file for a single (insn × envelope) pair.
   *  Returns the resulting stage-name (no extension) suitable for the
   *  Makefrag. The actual file is written under `cli.stage1OutputDir`.
   *
   *  Notes:
   *  - For now produces a *placeholder* per-iteration block with a
   *    representative envelope (LMUL=1, SEW=32) and the magic word.
   *    The full per-predicate × per-SEW × per-LMUL sweep is wired in
   *    later rounds (task10's POC gate + task11-15 fan-out).
   *  - File naming: `<insn>_<ext>-0.S` (upstream's dot-to-underscore
   *    + split-suffix scheme; split=0 for the placeholder).
   */
  def emitOne(insn: RvvInsn, cli: Cli, outDir: Path): String =
    val env  = VTypeEnvelope.unsafe(
      VType(Sew.Sew32, Lmul.M1, Vta.Agnostic, Vma.Agnostic),
      vl   = 4,
      vlen = cli.vlen,
      xlen = cli.xlen)
    val flatName = insn.name.replace('.', '_')
    val baseName = s"${flatName}_${insn.extension}-0"
    val fileName = s"$baseName.S"

    val block = TestSEmit.TestBlock(
      envelope    = env,
      vectorGroup = 8,
      vxsat       = false,
      insnAsm     = s"# placeholder for ${insn.name} ${insn.schema.formatString}",
      setupAsm    = Nil,
      dataLabel   = None)
    val envMacro = TestSEmit.envMacro(cli.xlen, hasFullV(cli.march))
    val content  = TestSEmit.render(
      name        = insn.name,
      envName     = envMacro,
      blocks      = List(block),
      testData    = Vector.empty,
      resultBytes = 64)
    Files.createDirectories(outDir)
    Files.write(outDir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8))
    baseName

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

/** Central registry of RvvInsn declarations. Populated by task10/11/13/
 *  14/15 fan-out work; remains empty in task8 so the Driver can compile
 *  and the CLI surface can be tested.
 */
object RvvInsnRegistry:
  val all: List[RvvInsn] = Nil // populated by per-instruction declarations in insns/
