// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.unittest

import me.jiuyang.rvprobe.rvv.vtype.Sew

import java.io.{ByteArrayOutputStream, File}
import java.nio.file.{Files, Path, Paths}

/** Subprocess wrapper around the upstream `testfloat_gen` binary.
 *  Per DEC-4, FP operand generation for ordinary FP instructions stays
 *  with testfloat3 (not predicate-driven). This driver invokes
 *  testfloat_gen with the appropriate flags and parses its output into
 *  a byte buffer ready to embed into the `.S` data section.
 *
 *  Resolution order for the binary:
 *    1. `RVPROBE_TESTFLOAT_GEN` env var (if set, must point at the binary)
 *    2. `testfloat_gen` on `PATH`
 *    3. `<workspace>/riscv-vector-tests/testfloat3/build/testfloat_gen`
 *
 *  If none of the above resolve, `Testfloat3Driver.generate(...)`
 *  returns `Left("testfloat3 binary not found ...")`; callers fall
 *  back to the structural placeholder so non-FP rounds still pass.
 */
object Testfloat3Driver:

  /** A single testfloat3 invocation: the FP operation, the FP width
   *  (16/32/64), the rounding mode, and the testlevel (1 or 2).
   */
  final case class Request(
    operation:   String,    // e.g., "f32_add", "f64_mul" — testfloat_gen op codes
    sew:         Sew,
    rmFlag:      String,    // "-rnear_even", "-rminMag", "-rmin", "-rmax", "-rnear_maxMag"
    testLevel:   Int)       // 1 or 2

  /** Resolve the testfloat_gen binary. Returns `None` if not found. */
  def resolveBinary(): Option[Path] =
    sys.env.get("RVPROBE_TESTFLOAT_GEN")
      .map(Paths.get(_))
      .filter(Files.isExecutable)
      .orElse {
        // Walk PATH
        sys.env.get("PATH").flatMap { p =>
          p.split(File.pathSeparator).toList
            .map(dir => Paths.get(dir).resolve("testfloat_gen"))
            .find(Files.isExecutable)
        }
      }
      .orElse {
        // Workspace-relative fallback
        val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath
        LazyList.iterate(cwd: Path)(_.getParent).takeWhile(_ != null)
          .map(_.resolve("riscv-vector-tests/testfloat3/build/testfloat_gen"))
          .find(Files.isExecutable)
      }

  /** Generate FP operand+result bytes for one (op, sew, rm) request.
   *  Returns `Left` on missing binary or subprocess failure; callers
   *  fall back to structural placeholder.
   */
  def generate(req: Request): Either[String, Array[Byte]] =
    resolveBinary() match
      case None =>
        Left(
          "testfloat3 binary not found (set RVPROBE_TESTFLOAT_GEN env var, " +
            "or build riscv-vector-tests/testfloat3 with `make` to produce " +
            "build/testfloat_gen)")
      case Some(bin) =>
        val args = List(
          bin.toString,
          s"-level", req.testLevel.toString,
          req.rmFlag,
          s"-${req.sew.bits}",
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
          else Right(out.toByteArray)
        catch
          case e: Exception => Left(s"testfloat_gen invocation failed: ${e.getMessage}")

  /** All 5 FRM modes, in upstream's iteration order. */
  val AllFrm: List[(String, String)] = List(
    "RNE" -> "-rnear_even",
    "RTZ" -> "-rminMag",
    "RDN" -> "-rmin",
    "RUP" -> "-rmax",
    "RMM" -> "-rnear_maxMag")
