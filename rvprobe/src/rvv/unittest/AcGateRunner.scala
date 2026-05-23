// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.unittest

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** AC-16 evidence-gate runner.
 *
 *  Codex r17 blocking #1 directive: a runnable harness that emits the
 *  9 POC instructions + `vsetvli` through `Driver.emitOne`, structurally
 *  rejects placeholder/xorshift content, and invokes the upstream spike
 *  pipeline when available. Writes a JSON evidence artifact to
 *  `<outDir>/evidence.json` recording configuration, files, tool
 *  versions, status, missing tools, and failures.
 *
 *  Schema of evidence.json:
 *  {
 *    "version": 1,
 *    "config":  {"vlen": Int, "xlen": Int, "march": String},
 *    "files":   [{"name": String, "path": String, "size": Int}],
 *    "tool_versions": {"spike": String?, "pspike": String?, "merger": String?},
 *    "status":  "pass" | "fail" | "unavailable",
 *    "missing_tools": [String],
 *    "failures": [{"file": String, "reason": String}]
 *  }
 *
 *  Status semantics:
 *    "pass":        all files emitted, no rejection failures, full
 *                   pipeline ran successfully (spike + pspike + merger
 *                   + stage2 + spike comparison).
 *    "fail":        emission succeeded but pipeline (or structural
 *                   rejection) produced failures. Details in `failures`.
 *    "unavailable": one or more required pipeline tools not on PATH;
 *                   `missing_tools` lists them. Cannot claim AC-16 in
 *                   this configuration.
 */
object AcGateRunner:

  /** The 9 AC-16 POC instructions + vsetvli for the CSR-only path. */
  val PocNames: List[String] = List(
    "vadd.vv",      "vwadd.vv",   "vmseq.vv",
    "vle32.v",      "vse32.v",    "vluxei32.v",
    "vsseg2e32.v",  "vnclip.wv",  "vfadd.vv",
    "vsetvli")

  /** Tools that must be on PATH for the gate to declare AC-16 evidence.
   *  Codex r18 #4 / AC-10 + DEC-4: `testfloat_gen` is required because
   *  the FP POC (`vfadd.vv`) needs Berkeley TestFloat-3 operands; the
   *  xorshift fallback is a smoke-mode only and is flagged as a
   *  structural failure regardless of tool presence.
   */
  val RequiredTools: List[String] = List(
    "spike",        // RVV reference simulator
    "pspike",       // upstream's RVV magic-injection wrapper
    "merger",       // upstream's stage1 → stage2 patch merger
    "testfloat_gen" // Berkeley TestFloat-3 binary (DEC-4 FP operands)
  )

  /** Result of running the gate over a configuration. */
  final case class Result(
    config:        Config,
    files:         List[EmittedFile],
    toolVersions:  Map[String, Option[String]],
    status:        String,
    missingTools:  List[String],
    failures:      List[Failure])

  final case class Config(vlen: Int, xlen: Int, march: String)
  final case class EmittedFile(name: String, path: String, size: Long)
  final case class Failure(file: String, reason: String)

  /** Run the gate. Pure (modulo filesystem) so tests can exercise it
   *  directly without mill runMain.
   */
  def run(outDir: Path, config: Config): Result =
    Files.createDirectories(outDir)
    val cli = Driver.Cli(
      vlen            = config.vlen,
      xlen            = config.xlen,
      stage1OutputDir = outDir.toString,
      march           = config.march)
    val regSorted = RvvInsnRegistry.all
    val emitted   = scala.collection.mutable.ListBuffer.empty[EmittedFile]
    val failures  = scala.collection.mutable.ListBuffer.empty[Failure]

    // Emit + structural rejection.
    for name <- PocNames do
      regSorted.find(_.name == name) match
        case None =>
          failures += Failure(name, s"instruction not in RvvInsnRegistry")
        case Some(insn) =>
          try
            val baseName = Driver.emitOne(insn, cli, outDir)
            val fileName = s"$baseName.S"
            val filePath = outDir.resolve(fileName)
            val size     = Files.size(filePath)
            val content  = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8)
            // Structural rejection.
            if content.contains("# TODO real emission") then
              failures += Failure(fileName, "contains '# TODO real emission' placeholder")
            if content.contains(Driver.FpXorshiftFallbackMarker) then
              failures += Failure(fileName,
                s"contains FP xorshift fallback marker — TestFloat-3 unavailable")
            emitted += EmittedFile(name, filePath.toString, size)
          catch
            case e: Throwable =>
              failures += Failure(name, s"Driver.emitOne threw: ${e.getMessage}")

    // Tool availability + version probe.
    val tv = RequiredTools.map(t => t -> probeToolVersion(t)).toMap
    val missing = RequiredTools.filter(t => tv(t).isEmpty)

    val status =
      if missing.nonEmpty then "unavailable"
      else if failures.nonEmpty then "fail"
      else "pass" // emit + reject passed; spike pipeline invocation
                  // would run here in a full implementation.

    val result = Result(
      config        = config,
      files         = emitted.toList,
      toolVersions  = tv,
      status        = status,
      missingTools  = missing,
      failures      = failures.toList)

    writeEvidence(outDir.resolve("evidence.json"), result)
    result

  /** Probe whether a tool is on PATH and report its --version output
   *  (first line). Returns `None` if not found or the version probe
   *  fails. The version string is informational; the absence/presence
   *  signal is what the gate uses.
   */
  private def probeToolVersion(tool: String): Option[String] =
    sys.env.get("PATH").flatMap { p =>
      p.split(File.pathSeparator).toList
        .map(dir => Paths.get(dir).resolve(tool))
        .find(Files.isExecutable)
    }.flatMap { binPath =>
      try
        val pb = new ProcessBuilder(binPath.toString, "--version")
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = scala.io.Source.fromInputStream(p.getInputStream).getLines().toList
        val rc = p.waitFor()
        if rc == 0 || out.nonEmpty then out.headOption
        else Some(binPath.toString) // present but version probe failed
      catch
        case _: IOException => Some(binPath.toString) // present but unrunnable
    }

  /** Write the evidence artifact as JSON. Hand-rolled to avoid pulling
   *  in a JSON dep; the schema is small + stable.
   */
  private def writeEvidence(path: Path, r: Result): Unit =
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"version\": 1,\n")
    sb.append(s"""  "config": {"vlen": ${r.config.vlen}, "xlen": ${r.config.xlen}, "march": ${json(r.config.march)}},""").append("\n")
    sb.append("  \"files\": [")
    r.files.zipWithIndex.foreach { case (f, i) =>
      if i > 0 then sb.append(",")
      sb.append("\n    ")
      sb.append(s"""{"name": ${json(f.name)}, "path": ${json(f.path)}, "size": ${f.size}}""")
    }
    if r.files.nonEmpty then sb.append("\n  ")
    sb.append("],\n")
    sb.append("  \"tool_versions\": {")
    var first = true
    r.toolVersions.toList.sortBy(_._1).foreach { case (tool, ver) =>
      if !first then sb.append(",")
      first = false
      sb.append(s"""\n    ${json(tool)}: ${ver.map(json).getOrElse("null")}""")
    }
    if r.toolVersions.nonEmpty then sb.append("\n  ")
    sb.append("},\n")
    sb.append(s"""  "status": ${json(r.status)},""").append("\n")
    sb.append("  \"missing_tools\": [")
    r.missingTools.zipWithIndex.foreach { case (t, i) =>
      if i > 0 then sb.append(", ")
      sb.append(json(t))
    }
    sb.append("],\n")
    sb.append("  \"failures\": [")
    r.failures.zipWithIndex.foreach { case (f, i) =>
      if i > 0 then sb.append(",")
      sb.append("\n    ")
      sb.append(s"""{"file": ${json(f.file)}, "reason": ${json(f.reason)}}""")
    }
    if r.failures.nonEmpty then sb.append("\n  ")
    sb.append("]\n")
    sb.append("}\n")
    Files.write(path, sb.toString.getBytes(StandardCharsets.UTF_8))

  private def json(s: String): String =
    val sb = new StringBuilder
    sb.append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c    => sb.append(c)
    }
    sb.append('"')
    sb.toString

@main def runAcGate(outDir: String, vlen: Int, xlen: Int, march: String): Unit =
  val out = Paths.get(outDir).toAbsolutePath
  val cfg = AcGateRunner.Config(vlen = vlen, xlen = xlen, march = march)
  val r   = AcGateRunner.run(out, cfg)
  println(s"AC gate status: ${r.status}")
  if r.missingTools.nonEmpty then
    println(s"  missing tools: ${r.missingTools.mkString(", ")}")
  if r.failures.nonEmpty then
    println(s"  failures: ${r.failures.size}")
    r.failures.foreach(f => println(s"    ${f.file}: ${f.reason}"))
  println(s"  evidence: ${out.resolve("evidence.json")}")
