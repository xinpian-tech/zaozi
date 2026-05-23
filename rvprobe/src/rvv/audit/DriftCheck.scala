// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.audit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

/** AC-15: TOML-drift CI check. Compares upstream `.toml` content
 *  hashes against the committed `audit/snapshots/<ext>/<name>.json`
 *  entries (`contentHash` field). Fails red when upstream drifts;
 *  refresh workflow is the 3-commit pattern (pin upstream commit +
 *  regen audit + human-reviewed predicate diff).
 *
 *  @main `runDriftCheck` returns exit code 1 when drift is detected,
 *  exit code 0 when in-sync.
 */
object DriftCheck:

  final case class Drift(
    extension:  String,
    name:       String,
    upstream:   String,
    snapshot:   String)

  def computeUpstreamHash(file: Path): String =
    val bytes = Files.readAllBytes(file)
    val md    = MessageDigest.getInstance("SHA-256")
    md.update(bytes)
    md.digest().map(b => "%02x".format(b & 0xff)).mkString

  def readSnapshotHash(file: Path): Option[String] =
    if !Files.exists(file) then None
    else
      val text   = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      val hashRe = """"contentHash"\s*:\s*"([0-9a-f]{64})"""".r
      hashRe.findFirstMatchIn(text).map(_.group(1))

  /** Returns a list of drifts. Empty means in-sync. */
  def check(upstreamConfigsRoot: Path, snapshotsRoot: Path): List[Drift] =
    import scala.jdk.CollectionConverters.*
    val drifts = List.newBuilder[Drift]
    Files.walk(upstreamConfigsRoot).iterator.asScala.foreach { p =>
      if Files.isRegularFile(p) && p.getFileName.toString.endsWith(".toml") then
        val ext       = p.getParent.getFileName.toString
        val name      = p.getFileName.toString.stripSuffix(".toml")
        val upHash    = computeUpstreamHash(p)
        val snapFile  = snapshotsRoot.resolve(ext).resolve(s"$name.json")
        readSnapshotHash(snapFile) match
          case Some(snapHash) if snapHash == upHash => ()
          case Some(snapHash)                       =>
            drifts += Drift(ext, name, upHash, snapHash)
          case None                                 =>
            drifts += Drift(ext, name, upHash, "<missing snapshot>")
    }
    drifts.result()

  @main def runDriftCheck(): Unit =
    val cwd       = Paths.get(System.getProperty("user.dir"))
    val upstream  = sys.env.get("RVPROBE_RVV_TESTS_CONFIGS").map(Paths.get(_)).orElse {
      LazyList.iterate(cwd: Path)(_.getParent).takeWhile(_ != null)
        .find(p => Files.exists(p.resolve("riscv-vector-tests/configs")))
        .map(_.resolve("riscv-vector-tests/configs"))
    }.getOrElse {
      Console.err.println("upstream configs/ not found (set RVPROBE_RVV_TESTS_CONFIGS)")
      sys.exit(2)
    }
    val snapshots = LazyList.iterate(cwd: Path)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("rvprobe/src/rvv/audit/snapshots")))
      .map(_.resolve("rvprobe/src/rvv/audit/snapshots"))
      .getOrElse {
        Console.err.println("rvprobe audit snapshots not found")
        sys.exit(2)
      }
    val drifts = check(upstream, snapshots)
    if drifts.isEmpty then
      println("TOML drift check: in-sync (every upstream toml hash matches snapshot)")
      sys.exit(0)
    else
      println(s"TOML drift check: ${drifts.size} drifts detected. Refresh workflow:")
      println("  1. Bump pinned upstream commit ref.")
      println("  2. Re-run rvprobe.runMain me.jiuyang.rvprobe.rvv.audit.writeAuditFixtures rvprobe/src/rvv/audit")
      println("  3. Re-run rvprobe.runMain me.jiuyang.rvprobe.rvv.audit.regenerateInsns rvprobe/src/rvv/insns")
      println("  4. Human-review the predicate diff; commit all three together.")
      println("")
      println("Drifts:")
      drifts.foreach(d => println(s"  ${d.extension}/${d.name}.toml  upstream=${d.upstream.take(12)}  snapshot=${d.snapshot.take(12)}"))
      sys.exit(1)
