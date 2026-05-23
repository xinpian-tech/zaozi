// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.unittest

import me.jiuyang.rvprobe.rvv.vtype.Sew

import java.io.{ByteArrayOutputStream, File}
import java.nio.file.{Files, Path, Paths}

/** Subprocess wrapper around Berkeley TestFloat-3's `testfloat_gen`
 *  binary. Per DEC-4, FP operand generation for ordinary FP
 *  instructions stays with testfloat3 (not predicate-driven).
 *
 *  Codex r12 #3 (HIGH): the upstream `riscv-vector-tests/testfloat3/`
 *  package is a Go-cgo wrapper around C functions (gen_a_f32,
 *  gen_ab_f32, ...) — NOT a standalone binary. To use TestFloat-3 from
 *  Scala without cgo/JNI, this driver invokes the standalone
 *  `testfloat_gen` binary that ships with Berkeley TestFloat-3 (a
 *  separate download from www.jhauser.us/arithmetic/TestFloat-3.html
 *  or buildable from the SoftFloat-3 source tree).
 *
 *  Berkeley TestFloat-3 `testfloat_gen` CLI:
 *    testfloat_gen [-level <N>] [-rnear_even|-rminMag|-rmin|-rmax|-rnear_maxMag]
 *                  [-tininessbefore|-tininessafter] <function>
 *
 *  `<function>` is e.g. `f32_add`, `f64_mul`, `f16_div` — the SEW is
 *  encoded in the function name. (Round 11/12's wrong CLI form passed
 *  a separate `-<sew>` flag, which Berkeley TestFloat-3 rejects.)
 *
 *  Output: ASCII text, one test case per line, whitespace-separated
 *  hex values. For a 2-operand function: `<a> <b> <result> <flags>`.
 *  Operand width matches the function (8 hex digits for f32, 4 for
 *  f16, 16 for f64). `<flags>` is a 2-hex-digit IEEE exception mask.
 *
 *  This driver:
 *   1. Resolves the binary via `RVPROBE_TESTFLOAT_GEN`, PATH, or a
 *      workspace-relative TestFloat-3 install (Nix flake build).
 *   2. Invokes with the correct CLI form (no spurious `-<sew>`).
 *   3. Parses the ASCII output back into raw little-endian operand
 *      bytes ready to embed into a `.S` data section.
 *
 *  Returns Left(...) gracefully when the binary is absent so non-FP
 *  rounds and CI-without-TestFloat-3 builds continue to function;
 *  callers (Driver.emitFp) decide whether to use emitFpFallback
 *  (xorshift operands) or fail loudly.
 */
object Testfloat3Driver:

  /** A single testfloat3 invocation: the FP operation, the FP width
   *  (16/32/64), the rounding mode flag, and the testlevel (1 or 2).
   */
  final case class Request(
    operation:   String,    // e.g., "f32_add", "f64_mul"
    sew:         Sew,
    rmFlag:      String,    // "-rnear_even", "-rminMag", "-rmin", "-rmax", "-rnear_maxMag"
    testLevel:   Int)       // 1 or 2

  /** Resolve the testfloat_gen binary. Returns `None` if not found.
   *  Search order: RVPROBE_TESTFLOAT_GEN env var > PATH > workspace.
   */
  def resolveBinary(): Option[Path] =
    sys.env.get("RVPROBE_TESTFLOAT_GEN")
      .map(Paths.get(_))
      .filter(Files.isExecutable)
      .orElse {
        sys.env.get("PATH").flatMap { p =>
          p.split(File.pathSeparator).toList
            .map(dir => Paths.get(dir).resolve("testfloat_gen"))
            .find(Files.isExecutable)
        }
      }
      .orElse {
        val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
        LazyList.iterate(cwd: Path)(_.getParent).takeWhile(_ != null)
          .flatMap { p =>
            List(
              p.resolve("testfloat3/build/testfloat_gen"),
              p.resolve("riscv-vector-tests/testfloat3/build/testfloat_gen"))
          }
          .find(Files.isExecutable)
      }

  /** Generate FP operand bytes for one (op, sew, rm) request.
   *
   *  Returns Right(bytes) where `bytes` is the concatenation of all
   *  operand A values followed by all operand B values (raw
   *  little-endian, matching the layout the .S data section expects
   *  for two `vle<sew>.v v16/v24` loads).
   *
   *  Returns Left(reason) on:
   *   - binary not found
   *   - subprocess non-zero exit
   *   - output failed ASCII parsing
   */
  def generate(req: Request): Either[String, Array[Byte]] =
    resolveBinary() match
      case None =>
        Left(
          "testfloat3 binary not found (set RVPROBE_TESTFLOAT_GEN, " +
            "add testfloat_gen to PATH, or build " +
            "<workspace>/testfloat3 with the Berkeley TestFloat-3 sources)")
      case Some(bin) =>
        // Berkeley TestFloat-3 CLI: no `-<sew>` flag; SEW is in op name.
        val args = List(
          bin.toString,
          "-level", req.testLevel.toString,
          req.rmFlag,
          req.operation)
        try
          val pb = new ProcessBuilder(args*)
          pb.redirectErrorStream(false)
          val p   = pb.start()
          val out = new ByteArrayOutputStream
          val buf = new Array[Byte](4096)
          val is  = p.getInputStream
          var n   = is.read(buf)
          while n > 0 do
            out.write(buf, 0, n)
            n = is.read(buf)
          val rc = p.waitFor()
          if rc != 0 then
            Left(s"testfloat_gen exited $rc for ${args.mkString(" ")}")
          else parseAsciiOutput(out.toByteArray, req.sew)
        catch
          case e: Exception => Left(s"testfloat_gen invocation failed: ${e.getMessage}")

  /** Parse Berkeley TestFloat-3 ASCII output. Each non-empty line is
   *  one test case with whitespace-separated hex tokens. For a 2-op
   *  function (`fN_add`), there are 4 tokens: a, b, result, flags.
   *  We keep only `a` and `b` (operands); the SUT computes the result
   *  at runtime, and we compare against pspike's reference.
   *
   *  Returns concatenation: a0,a1,...,aN,b0,b1,...,bN as little-endian
   *  bytes matching the SEW width.
   */
  private[rvprobe] def parseAsciiOutput(raw: Array[Byte], sew: Sew): Either[String, Array[Byte]] =
    val text   = new String(raw, java.nio.charset.StandardCharsets.US_ASCII)
    val lines  = text.split('\n').iterator.map(_.trim).filter(_.nonEmpty).toList
    if lines.isEmpty then
      Left("testfloat_gen produced no output lines")
    else
      val bytesPerOp = sew.bits / 8
      val aBuf  = collection.mutable.ArrayBuffer.empty[Byte]
      val bBuf  = collection.mutable.ArrayBuffer.empty[Byte]
      var errorReason: Option[String] = None
      lines.foreach { line =>
        if errorReason.isEmpty then
          val toks = line.split("\\s+").toList
          if toks.size < 2 then
            errorReason = Some(s"expected ≥2 hex tokens per line, got: $line")
          else
            val aLE = hexToLittleEndian(toks(0), bytesPerOp)
            val bLE = hexToLittleEndian(toks(1), bytesPerOp)
            (aLE, bLE) match
              case (Right(aB), Right(bB)) =>
                aBuf ++= aB
                bBuf ++= bB
              case (Left(e), _) => errorReason = Some(e)
              case (_, Left(e)) => errorReason = Some(e)
      }
      errorReason match
        case Some(reason) => Left(reason)
        case None         => Right((aBuf ++ bBuf).toArray)

  /** Convert a hex string to little-endian bytes, padded/truncated to
   *  `width` bytes. testfloat_gen emits big-endian-style hex (high
   *  nibble first); RISC-V is little-endian, so we reverse.
   */
  private[rvprobe] def hexToLittleEndian(hex: String, width: Int): Either[String, Array[Byte]] =
    val cleaned = hex.stripPrefix("0x").stripPrefix("0X")
    if !cleaned.forall(c => "0123456789abcdefABCDEF".contains(c)) then
      Left(s"non-hex token: $hex")
    else
      val padded = if cleaned.length < width * 2
                   then "0" * (width * 2 - cleaned.length) + cleaned
                   else cleaned.takeRight(width * 2)
      val be = padded.grouped(2).map(s => Integer.parseInt(s, 16).toByte).toArray
      Right(be.reverse) // BE → LE

  /** All 5 FRM modes, in upstream's iteration order. Berkeley
   *  TestFloat-3 flag names match RVV's `frm` semantics:
   *    RNE = round to nearest even         (-rnear_even)
   *    RTZ = round toward zero             (-rminMag)
   *    RDN = round down toward -inf        (-rmin)
   *    RUP = round up toward +inf          (-rmax)
   *    RMM = round to nearest, ties to max magnitude (-rnear_maxMag)
   */
  val AllFrm: List[(String, String)] = List(
    "RNE" -> "-rnear_even",
    "RTZ" -> "-rminMag",
    "RDN" -> "-rmin",
    "RUP" -> "-rmax",
    "RMM" -> "-rnear_maxMag")
