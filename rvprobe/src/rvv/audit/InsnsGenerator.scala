// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.audit

import me.jiuyang.rvprobe.rvv.Schema

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Generates per-extension `RvvInsn` declarations from the committed
 *  audit snapshots. Reads `audit/snapshots/<ext>/<name>.json`, infers
 *  schema reference + indexedEew + nfields + vxrm/vxsat/notestfloat3
 *  metadata, and emits one Scala source file per extension under
 *  `rvprobe/src/rvv/insns/<Ext>.scala`.
 *
 *  After running this @main, the Scala source tree contains
 *  `RvvInsnRegistry.all` populated with one entry per upstream toml
 *  (676 in total). The generator is rerun whenever the audit
 *  snapshots refresh (per AC-15's drift CI workflow).
 */
object InsnsGenerator:

  /** Minimal snapshot fields used by this generator. */
  final case class GenInfo(
    extension:    String,
    name:         String,
    format:       String,
    vxrm:         Boolean,
    vxsat:        Boolean,
    notestfloat3: Boolean)

  private val JsonStrRe = """"([a-z0-9_-]+)"\s*:\s*"([^"]*)"""".r
  private val JsonBoolRe = """"([a-z0-9_]+)"\s*:\s*(true|false)""".r

  /** Read a snapshot JSON file and extract the metadata fields. The
   *  snapshot format is hand-rolled JSON; we only need the top-level
   *  keys (extension, name, format, vxrm, vxsat, notestfloat3), so a
   *  regex-based parser suffices.
   */
  def readSnapshot(file: Path): Option[GenInfo] =
    if !Files.exists(file) then None
    else
      val text     = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      val strs     = JsonStrRe.findAllMatchIn(text).map(m => m.group(1) -> m.group(2)).toMap
      val bools    = JsonBoolRe.findAllMatchIn(text).map(m => m.group(1) -> m.group(2).toBoolean).toMap
      for
        ext    <- strs.get("extension")
        name   <- strs.get("name")
        format <- strs.get("format")
      yield GenInfo(
        ext, name, format,
        vxrm         = bools.getOrElse("vxrm", false),
        vxsat        = bools.getOrElse("vxsat", false),
        notestfloat3 = bools.getOrElse("notestfloat3", false))

  /** Infer the Scala Schema enum name for a given format string. */
  def schemaEnumName(format: String): Option[String] =
    Schema.byFormatString(format).map(_.toString)

  /** Infer indexedEew from instruction name for indexed load/store +
   *  segmented indexed variants. Recognizes:
   *    vluxei<n>.v, vloxei<n>.v       (unit indexed load)
   *    vsuxei<n>.v, vsoxei<n>.v       (unit indexed store)
   *    vluxseg<f>ei<n>.v, vloxseg<f>ei<n>.v (segmented indexed load)
   *    vsuxseg<f>ei<n>.v, vsoxseg<f>ei<n>.v (segmented indexed store)
   *
   *  Codex r13 #3: round-13 regex `vlsseg\d*ei` matched strided
   *  segmented (which has no indexedEew); the segmented-indexed forms
   *  (`vluxseg2ei32`, etc.) were missed entirely.
   */
  def indexedEewFor(name: String): Option[Int] =
    val rx = """(?:vluxei|vloxei|vsuxei|vsoxei|vluxseg\d+ei|vloxseg\d+ei|vsuxseg\d+ei|vsoxseg\d+ei)(\d+)""".r
    rx.findFirstMatchIn(name).map(_.group(1).toInt)

  /** Infer NFIELDS from segmented load/store name. Recognizes:
   *    vlseg<f>e<m>.v, vsseg<f>e<m>.v       (unit-stride segmented)
   *    vlsseg<f>e<m>.v, vssseg<f>e<m>.v     (strided segmented)
   *    vluxseg<f>ei<m>.v, vloxseg<f>ei<m>.v (indexed-unordered segmented load)
   *    vsuxseg<f>ei<m>.v, vsoxseg<f>ei<m>.v (indexed-unordered segmented store)
   *
   *  Codex r13 #3 + #4: round-13 regex caught only some of these; the
   *  segmented indexed and strided segmented forms returned 1 when
   *  they should have returned the field count from the name.
   */
  def nfieldsFor(name: String): Int =
    // Pattern 1: vlseg<N>e<M> or vsseg<N>e<M> (unit-stride).
    val rxUnit = """^v(?:l|s)seg(\d+)e\d+""".r
    rxUnit.findFirstMatchIn(name).map(_.group(1).toInt)
      .orElse {
        // Pattern 2: vlsseg<N>e<M> or vssseg<N>e<M> (strided).
        val rxStr = """^v(?:l|s)sseg(\d+)e\d+""".r
        rxStr.findFirstMatchIn(name).map(_.group(1).toInt)
      }
      .orElse {
        // Pattern 3: vluxseg/vloxseg/vsuxseg/vsoxseg <N>ei<M> (indexed).
        val rxIdx = """^v(?:l|s)(?:u|o)xseg(\d+)ei\d+""".r
        rxIdx.findFirstMatchIn(name).map(_.group(1).toInt)
      }
      .getOrElse(1)

  /** Infer the widthProfile from the instruction name pattern.
   *  Codex round-9 #1: without this inference, real generated
   *  declarations for `vwadd.vv`, `vmseq.vv`, `vnclip.wv` etc. ship
   *  with `OperandWidthProfile.default`, and Driver computes base-
   *  width results for them.
   *
   *  Returns the Scala source string for the `widthProfile = ...`
   *  field, or empty if the default profile suffices.
   */
  def widthProfileFor(name: String): String =
    val n = name.toLowerCase
    // Mask-producing comparisons: vd is a mask destination
    val isMask =
      n.startsWith("vmseq") || n.startsWith("vmsne") ||
        n.startsWith("vmslt") || n.startsWith("vmsltu") ||
        n.startsWith("vmsle") || n.startsWith("vmsleu") ||
        n.startsWith("vmsgt") || n.startsWith("vmsgtu") ||
        n.startsWith("vmsge") || n.startsWith("vmsgeu") ||
        n.startsWith("vmfeq") || n.startsWith("vmfne") ||
        n.startsWith("vmflt") || n.startsWith("vmfle") ||
        n.startsWith("vmfgt") || n.startsWith("vmfge") ||
        n.startsWith("vmadc") || n.startsWith("vmsbc") ||
        n.startsWith("vmand") || n.startsWith("vmor") || n.startsWith("vmxor") ||
        n.startsWith("vmnand") || n.startsWith("vmnor") || n.startsWith("vmxnor") ||
        n.startsWith("vmandn") || n.startsWith("vmorn")
    if isMask then
      "    widthProfile = OperandWidthProfile.maskDestination(),"
    else
      // Codex r11 #6a: narrowing patterns include vfncvt.*.w (ends in
      // `.w` not `.wv`/`.wx` etc) and BF16 vfncvtbf16.*.w. Extend the
      // narrowing detector.
      val isNarrowingVd = (n.startsWith("vn") || n.startsWith("vfn")) &&
        (n.endsWith(".wv") || n.endsWith(".wx") || n.endsWith(".wi") ||
         n.endsWith(".wf") || n.endsWith(".w"))
      // Codex r11 #6b: widening reductions (vw*red* / vfw*red*) double
      // the dest EEW but KEEP base LMUL EMUL (single result register).
      // Exclude them from the By2 widening rule; the OperandWidthProfile
      // model would otherwise also double the EMUL, giving the wrong
      // register footprint.
      val isWideningReduction = (n.startsWith("vwred") || n.startsWith("vfwred"))
      val isWideningVd  = (n.startsWith("vw") || n.startsWith("vfw")) && !isWideningReduction
      val isWideningVs2 = isWideningVd && (n.endsWith(".wv") || n.endsWith(".wx") ||
        n.endsWith(".wf"))
      if isNarrowingVd then
        // vnclip.wv, vnsrl.wv, vfncvt.f.f.w etc: vs2 is wide, vd is narrow.
        """    widthProfile = OperandWidthProfile(Map(me.jiuyang.rvprobe.rvv.OperandRole.Vs2 -> me.jiuyang.rvprobe.rvv.eew.WidthScale.By2)),"""
      else if isWideningVs2 then
        // vwadd.wv, vwsub.wv: both vd and vs2 are wider than vs1.
        """    widthProfile = OperandWidthProfile(Map(me.jiuyang.rvprobe.rvv.OperandRole.Vd -> me.jiuyang.rvprobe.rvv.eew.WidthScale.By2, me.jiuyang.rvprobe.rvv.OperandRole.Vs2 -> me.jiuyang.rvprobe.rvv.eew.WidthScale.By2)),"""
      else if isWideningVd then
        // vwadd.vv, vwsub.vv, vwmul.vv, vfwadd.vv etc: only vd wider.
        """    widthProfile = OperandWidthProfile(Map(me.jiuyang.rvprobe.rvv.OperandRole.Vd -> me.jiuyang.rvprobe.rvv.eew.WidthScale.By2)),"""
      else ""

  /** Render an RvvInsn declaration for a single info entry. */
  def renderInsn(info: GenInfo): String =
    val schemaName = schemaEnumName(info.format).getOrElse {
      throw new IllegalStateException(
        s"InsnsGenerator: unknown schema format `${info.format}` for ${info.extension}/${info.name}. " +
          s"Either Schema.scala is missing a sealed-family entry, or the audit snapshot is stale.")
    }
    val sb         = new StringBuilder
    val vName      = info.name.replace('.', '_').replace('-', '_')
    sb.append(s"  // ${info.name}  (format: ${info.format})\n")
    sb.append(s"  val `$vName`: RvvInsn = RvvInsn(\n")
    sb.append(s"""    name       = "${info.name}",\n""")
    sb.append(s"""    extension  = "${info.extension}",\n""")
    sb.append(s"""    sourceToml = "${info.extension}/${info.name}.toml",\n""")
    val wp = widthProfileFor(info.name)
    if wp.nonEmpty then sb.append(wp).append("\n")
    sb.append(s"    schema     = Schema.$schemaName")
    indexedEewFor(info.name).foreach(e => sb.append(s",\n    indexedEew = Some($e)"))
    val nf = nfieldsFor(info.name)
    if nf > 1 then sb.append(s",\n    nfields = $nf")
    if info.vxrm then sb.append(",\n    vxrm = true")
    if info.vxsat then sb.append(",\n    vxsat = true")
    if info.notestfloat3 then sb.append(",\n    notestfloat3 = true")
    sb.append(")\n\n")
    sb.toString

  /** Render an extension-specific Scala source file.
   *
   *  Three modes:
   *  - `chunkSuffix=None, allChunks=None`: single-file mode for small
   *    extensions (one file with declarations + `all` list).
   *  - `chunkSuffix=Some(idx), allChunks=None`: per-chunk file
   *    (`<Ext>0.scala`, `<Ext>1.scala`, ...) carrying its slice of
   *    declarations + `chunkAll` list.
   *  - `chunkSuffix=None, allChunks=Some(n)`: top-level aggregator
   *    (`<Ext>.scala`) that concatenates `<Ext>0.chunkAll ++ ... ++
   *    <Ext>(n-1).chunkAll` into `all`.
   */
  def renderExtensionFile(
    extName:     String,
    insns:       List[GenInfo],
    chunkSuffix: Option[Int],
    allChunks:   Option[Int]
  ): String =
    val baseObj = extName.capitalize
    val objName = chunkSuffix match
      case Some(n) => s"$baseObj$n"
      case None    => baseObj
    val sb = new StringBuilder
    sb.append("// SPDX-License-Identifier: Apache-2.0\n")
    sb.append("// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>\n")
    sb.append("//\n")
    sb.append(s"// Auto-generated by InsnsGenerator from audit/snapshots/$extName/.\n")
    sb.append("// Do not edit by hand. Regenerate via:\n")
    sb.append("//   mill rvprobe.runMain me.jiuyang.rvprobe.rvv.audit.regenerateInsns\n")
    sb.append("package me.jiuyang.rvprobe.rvv.insns\n\n")
    allChunks match
      case Some(_) =>
        sb.append("import me.jiuyang.rvprobe.rvv.unittest.RvvInsn\n\n")
      case None    =>
        sb.append("import me.jiuyang.rvprobe.rvv.Schema\n")
        sb.append("import me.jiuyang.rvprobe.rvv.eew.OperandWidthProfile\n")
        sb.append("import me.jiuyang.rvprobe.rvv.unittest.RvvInsn\n\n")
    allChunks match
      case Some(n) =>
        // Aggregator object
        sb.append(s"/** Aggregator for extension `$extName` (${n} chunks). */\n")
        sb.append(s"object $baseObj:\n")
        sb.append(s"  val all: List[RvvInsn] =\n")
        val refs = (0 until n).map(i => s"$baseObj$i.chunkAll")
        sb.append("    " + refs.mkString(" ++\n    ") + "\n")
      case None    =>
        sb.append(s"/** ${insns.size} RvvInsn declarations for `$extName` (chunk${chunkSuffix.map(i => s" $i").getOrElse("")}). */\n")
        sb.append(s"object $objName:\n")
        for info <- insns do sb.append(renderInsn(info))
        val listVal = if chunkSuffix.isDefined then "chunkAll" else "all"
        sb.append(s"  val $listVal: List[RvvInsn] = List(\n")
        sb.append(insns.map { info =>
          val v = info.name.replace('.', '_').replace('-', '_')
          s"    `$v`"
        }.mkString(",\n"))
        sb.append("\n  )\n")
    sb.toString

  /** Maximum number of RvvInsn declarations per generated Scala file.
   *  JVM's per-method bytecode limit (64 KB) constrains the static
   *  initializer; with `sourceToml` added per declaration we need
   *  smaller chunks. 100 keeps each `<clinit>` well under the limit
   *  while keeping the file count manageable.
   */
  private val ChunkSize: Int = 100

  /** Walk the audit snapshots tree, group by extension, render
   *  per-extension Scala files (chunked when an extension's insn
   *  count exceeds ChunkSize) under `outDir`.
   */
  def regenerate(snapshotsRoot: Path, outDir: Path): List[(String, Int)] =
    import scala.jdk.CollectionConverters.*
    if !Files.isDirectory(snapshotsRoot) then
      throw new IllegalStateException(s"snapshots root not found: $snapshotsRoot")
    val byExt = scala.collection.mutable.Map.empty[String, List[GenInfo]].withDefaultValue(Nil)
    Files.walk(snapshotsRoot).iterator.asScala.foreach { p =>
      if Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json") then
        readSnapshot(p).foreach(info => byExt(info.extension) = info :: byExt(info.extension))
    }
    Files.createDirectories(outDir)
    val results = List.newBuilder[(String, Int)]
    byExt.toList.sortBy(_._1).foreach { case (ext, insns) =>
      val sorted = insns.sortBy(_.name)
      if sorted.size <= ChunkSize then
        // Single file for small extensions.
        val content = renderExtensionFile(ext, sorted, chunkSuffix = None, allChunks = None)
        Files.write(outDir.resolve(s"${ext.capitalize}.scala"),
                    content.getBytes(StandardCharsets.UTF_8))
      else
        // Chunk into <Ext>0.scala, <Ext>1.scala, ... and a top-level
        // <Ext>.scala aggregator.
        val chunks = sorted.grouped(ChunkSize).toList.zipWithIndex
        for (chunk, idx) <- chunks do
          val content = renderExtensionFile(ext, chunk, chunkSuffix = Some(idx), allChunks = None)
          Files.write(outDir.resolve(s"${ext.capitalize}$idx.scala"),
                      content.getBytes(StandardCharsets.UTF_8))
        val aggregator = renderExtensionFile(
          ext, Nil, chunkSuffix = None, allChunks = Some(chunks.size))
        Files.write(outDir.resolve(s"${ext.capitalize}.scala"),
                    aggregator.getBytes(StandardCharsets.UTF_8))
      results += (ext -> sorted.size)
    }
    // Emit the central RvvInsnRegistryGenerated.scala aggregating all.
    val regContent = renderRegistry(byExt.keys.toList.sorted)
    Files.write(outDir.resolve("RvvInsnRegistryGenerated.scala"),
                regContent.getBytes(StandardCharsets.UTF_8))
    results.result()

  private def renderRegistry(extensions: List[String]): String =
    val sb = new StringBuilder
    sb.append("// SPDX-License-Identifier: Apache-2.0\n")
    sb.append("// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>\n")
    sb.append("//\n")
    sb.append("// Auto-generated by InsnsGenerator. Do not edit by hand.\n")
    sb.append("package me.jiuyang.rvprobe.rvv.insns\n\n")
    sb.append("import me.jiuyang.rvprobe.rvv.unittest.RvvInsn\n\n")
    sb.append("/** Aggregate registry of all RvvInsn declarations across all upstream extensions. */\n")
    sb.append("object AllInsns:\n")
    sb.append("  val all: List[RvvInsn] =\n")
    val parts = extensions.map(e => s"${e.capitalize}.all")
    sb.append("    " + parts.mkString(" ++\n    ") + "\n")
    sb.toString

  @main def regenerateInsns(outDir: String = "rvprobe/src/rvv/insns"): Unit =
    val cwd        = Paths.get(System.getProperty("user.dir"))
    val snapsRoot  =
      if Files.exists(cwd.resolve("rvprobe/src/rvv/audit/snapshots")) then
        cwd.resolve("rvprobe/src/rvv/audit/snapshots")
      else
        // walk up to find project root
        LazyList.iterate(cwd: Path)(_.getParent).takeWhile(_ != null)
          .find(p => Files.exists(p.resolve("rvprobe/src/rvv/audit/snapshots")))
          .map(_.resolve("rvprobe/src/rvv/audit/snapshots"))
          .getOrElse(throw new IllegalStateException("audit snapshots not found"))
    val out = if Paths.get(outDir).isAbsolute then Paths.get(outDir)
              else snapsRoot.getParent.getParent.resolve("insns")
    val results = regenerate(snapsRoot, out)
    println(s"wrote ${results.size} extension files + RvvInsnRegistryGenerated under $out")
    results.foreach { case (ext, n) => println(s"  $ext: $n insns") }
