// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.audit

import me.jiuyang.rvprobe.rvv.pred.*
import me.jiuyang.rvprobe.rvv.vtype.Sew

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

/** Offline TOML intent classifier. Walks upstream
 *  `riscv-vector-tests/configs/<ext>/<name>.toml` files, parses the
 *  `[tests]` arrays, and classifies every literal-tuple into the
 *  predicate vocabulary under `me.jiuyang.rvprobe.rvv.pred`. Emits
 *  per-toml JSON snapshots and forward/backward audit reports.
 *
 *  Not on the runtime path. Used by `@main writeAuditFixtures` to
 *  regenerate `audit/snapshots/<ext>/<name>.json` whenever the upstream
 *  curator literals change (per AC-15's three-commit refresh workflow).
 */
object TomlIntent:

  // ---------- Parsing ----------

  final case class TomlSpec(
    extension:    String,
    name:         String,
    format:       String,
    vxrm:         Boolean,
    vxsat:        Boolean,
    notestfloat3: Boolean,
    tests:        Map[String, List[List[String]]],
    contentHash:  String)

  private val LegalKeys: Set[String] = Set(
    "base", "sew8", "sew16", "sew32", "sew64", "fsew16", "fsew32", "fsew64", "bf16sew16")

  def parse(extDir: String, file: Path): Either[String, TomlSpec] =
    if !Files.exists(file) then Left(s"file not found: $file")
    else
      val bytes        = Files.readAllBytes(file)
      val rawContent   = new String(bytes, StandardCharsets.UTF_8)
      val hash         = sha256(bytes) // hash of UNSTRIPPED content (drift CI compares upstream bytes)
      val content      = stripTomlComments(rawContent)
      val nameRe       = """name\s*=\s*"([^"]+)"""".r
      val formatRe     = """format\s*=\s*"([^"]+)"""".r
      val vxrmRe       = """vxrm\s*=\s*(true|false)""".r
      val vxsatRe      = """vxsat\s*=\s*(true|false)""".r
      val ntfRe        = """notestfloat3\s*=\s*(true|false)""".r

      val name         = nameRe.findFirstMatchIn(content).map(_.group(1)).getOrElse("")
      val format       = formatRe.findFirstMatchIn(content).map(_.group(1)).getOrElse("")
      val vxrm         = vxrmRe.findFirstMatchIn(content).exists(_.group(1) == "true")
      val vxsat        = vxsatRe.findFirstMatchIn(content).exists(_.group(1) == "true")
      val notestfloat3 = ntfRe.findFirstMatchIn(content).exists(_.group(1) == "true")

      // Structural validation (Codex round-5: fail-loud on malformed/incomplete tomls).
      val errors = List.newBuilder[String]
      if name.isEmpty then errors += s"missing or empty `name` field"
      if format.isEmpty then errors += s"missing or empty `format` field"

      val (tests, parseErrs) = extractTestsKeys(content)
      parseErrs.foreach(errors += _)

      val errList = errors.result()
      if errList.nonEmpty then Left(errList.mkString("; "))
      else Right(TomlSpec(extDir, name, format, vxrm, vxsat, notestfloat3, tests, hash))

  /** Extract every `<key> = [ ... ]` assignment from the (comment-
   *  stripped) content, anchored at line/key boundaries so `sew16`
   *  cannot collide with the `fsew16` / `bf16sew16` substrings.
   *  Returns the parsed `tests` map and any structural errors
   *  encountered (unknown key, duplicate key, unmatched bracket).
   */
  private def extractTestsKeys(content: String): (Map[String, List[List[String]]], List[String]) =
    val keyAssignRe = """(?m)^\s*([A-Za-z][A-Za-z0-9_]*)\s*=\s*\[""".r
    val tests       = scala.collection.mutable.LinkedHashMap.empty[String, List[List[String]]]
    val errors      = List.newBuilder[String]
    for m <- keyAssignRe.findAllMatchIn(content) do
      val key      = m.group(1)
      // Only consider tests-section keys; skip top-level scalar metadata
      // (name/format/vxrm/etc are matched separately and don't have `[`).
      val isTestsKey = LegalKeys.contains(key)
      val isTopLevelArray =
        // Heuristic: top-level scalars are not preceded by `[` (which only
        // appears in `[tests]` section header). For our toml shape, ALL test
        // arrays use `[`, so isTestsKey above is the right gate.
        false
      if isTestsKey then
        val openBracket = content.indexOf('[', m.end - 1)
        if openBracket < 0 then errors += s"key `$key` has no opening `[`"
        else
          val close = findMatchingBracket(content, openBracket)
          if close < 0 then errors += s"key `$key` has unmatched brackets"
          else
            val body = content.substring(openBracket + 1, close)
            if tests.contains(key) then errors += s"duplicate test key `$key`"
            else
              parseRowsLoud(body) match
                case Right(rows) =>
                  // Empty arrays are legitimate upstream patterns:
                  // vmv1r.v / vmsif.m / vsext.vf2 etc. omit certain
                  // SEWs intentionally. Keep them; the audit pass
                  // produces empty per-key entries which is fine.
                  tests(key) = rows
                case Left(msg)   => errors += s"key `$key`: $msg"
      else if !isTopLevelMetadataKey(key) then
        errors += s"unknown key `$key` in tests section"
    // Non-FP keys: every token must be a hex literal; non-hex tokens
    // (which silently coerced to BigInt(0)) are structural errors.
    val nonFpKeys = tests.keys.filter(k => !isFpKey(k)).toList
    for k <- nonFpKeys do
      for (row, ri) <- tests(k).zipWithIndex do
        for (tok, ti) <- row.zipWithIndex do
          if !looksLikeHexInteger(tok) then
            errors += s"key `$k` row $ri token $ti: not a hex integer literal: `$tok`"
    (tests.toMap, errors.result())

  /** Strict integer-token predicate. Rejects anything that
   *  parseHexBigInt would coerce to BigInt(0) (Codex round-7 #6).
   */
  private def looksLikeHexInteger(token: String): Boolean =
    val t = token.trim.stripPrefix("\"").stripSuffix("\"").trim
    if t.isEmpty then false
    else if t.startsWith("0x") || t.startsWith("0X") then
      t.drop(2).forall(c => "0123456789abcdefABCDEF".contains(c))
    else
      t.matches("^-?\\d+$")

  private val TopLevelMetadataKeys: Set[String] =
    Set("name", "format", "vxrm", "vxsat", "notestfloat3")

  private def isTopLevelMetadataKey(key: String): Boolean = TopLevelMetadataKeys.contains(key)

  /** Strip `# ... \n` comments outside quoted strings. TOML supports `#`
   *  comments anywhere on a line except inside string literals. This
   *  runs once on file load so the structural parsers below never see
   *  comments and the bracket-balancer can't be tricked by `]` inside a
   *  comment.
   */
  def stripTomlComments(content: String): String =
    val sb       = new StringBuilder(content.length)
    var i        = 0
    val n        = content.length
    var inString = false
    var escaped  = false
    while i < n do
      val c = content.charAt(i)
      if inString then
        sb.append(c)
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then inString = false
        i += 1
      else if c == '"' then
        sb.append(c)
        inString = true
        i += 1
      else if c == '#' then
        // Skip to (but keep) the newline so line numbers stay aligned.
        while i < n && content.charAt(i) != '\n' do i += 1
      else
        sb.append(c)
        i += 1
    sb.toString

  private def findMatchingBracket(content: String, openIdx: Int): Int =
    var depth    = 1
    var i        = openIdx + 1
    var inString = false
    var escaped  = false
    while i < content.length && depth > 0 do
      val c = content.charAt(i)
      if inString then
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then inString = false
      else
        if c == '"' then inString = true
        else if c == '[' then depth += 1
        else if c == ']' then depth -= 1
      i += 1
    if depth == 0 then i - 1 else -1

  /** Parse the body of an outer array as a sequence of `[...]` row
   *  literals separated by commas. Returns `Left` on malformed input
   *  (junk between rows, unmatched inner brackets, unterminated quoted
   *  tokens) per Codex round-6/7 fail-loud requirement.
   */
  private def parseRowsLoud(body: String): Either[String, List[List[String]]] =
    val rows = List.newBuilder[List[String]]
    var i    = 0
    val n    = body.length
    while i < n do
      while i < n && (body.charAt(i).isWhitespace || body.charAt(i) == ',') do i += 1
      if i < n then
        if body.charAt(i) != '[' then
          return Left(s"unexpected token at body offset $i: ${body.charAt(i)}; expected `[` or `,`")
        val close = findMatchingBracket(body, i)
        if close <= i then return Left(s"unmatched inner `[` at body offset $i")
        splitRowLoud(body.substring(i + 1, close)) match
          case Right(row) => rows += row
          case Left(msg)  => return Left(s"row at offset $i: $msg")
        i = close + 1
    Right(rows.result())

  /** Split a row like `0xf8, 0x00` or `"smallest_normal_float", "max_float"`
   *  into raw tokens. Returns `Left` on unterminated quoted strings.
   */
  private def splitRowLoud(rowBody: String): Either[String, List[String]] =
    val tokens = List.newBuilder[String]
    var i      = 0
    val n      = rowBody.length
    while i < n do
      while i < n && (rowBody.charAt(i).isWhitespace || rowBody.charAt(i) == ',') do i += 1
      if i < n then
        if rowBody.charAt(i) == '"' then
          val end = rowBody.indexOf('"', i + 1)
          if end <= i then return Left(s"unterminated quoted token starting at offset $i")
          tokens += rowBody.substring(i + 1, end)
          i = end + 1
        else
          val start = i
          while i < n && rowBody.charAt(i) != ',' && !rowBody.charAt(i).isWhitespace do i += 1
          val tok = rowBody.substring(start, i).trim
          if tok.nonEmpty then tokens += tok
    Right(tokens.result())

  /** Permissive wrapper kept for the public extractTestsKeys path
   *  (which surfaces the errors up to `parse`).
   */
  private def parseRows(body: String): List[List[String]] =
    parseRowsLoud(body).getOrElse(Nil)

  private def sha256(bytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(bytes)
    md.digest().map(b => "%02x".format(b & 0xff)).mkString

  // ---------- Classification ----------

  final case class ClassifiedRow(
    rawRow:        List[String],
    valuePreds:    List[List[ValuePred]],
    tuplePreds:    List[TuplePred],
    fpValuePreds:  List[List[FpValuePred]],
    fpTuplePreds:  List[FpTuplePred])

  final case class TomlSnapshot(
    spec:         TomlSpec,
    rowsByKey:    Map[String, List[ClassifiedRow]],
    litOnlyCount: Int)

  def classify(spec: TomlSpec): TomlSnapshot =
    val hint        = hintFor(spec.name)
    val rowsBuilder = Map.newBuilder[String, List[ClassifiedRow]]
    var litOnly     = 0

    /** A row is "lit-only" when every predicate it matched is a Lit variant
     *  (no named curator intent captured). Lit is acceptable per AC-3, but
     *  a high litOnly count signals room to grow the vocabulary.
     */
    def isLitOnly(row: ClassifiedRow): Boolean =
      val nonLitFound =
        row.valuePreds.flatten.exists {
          case _: ValuePred.Lit => false
          case _                => true
        } ||
          row.tuplePreds.exists {
            case _: TuplePred.Lit => false
            case _                => true
          } ||
          row.fpValuePreds.flatten.exists {
            case _: FpValuePred.FpLit => false
            case _                    => true
          } ||
          row.fpTuplePreds.exists {
            case _: FpTuplePred.Lit => false
            case _                  => true
          }
      !nonLitFound

    for (key, rows) <- spec.tests do
      val sewOpt = sewFor(key)
      val classified = rows.map { rawRow =>
        val row =
          if isFpKey(key) then
            val fpValues = rawRow.map(FpValuePred.classify)
            val fpTuples = FpTuplePred.classify(fpValues.flatten)
            ClassifiedRow(rawRow, Nil, Nil, fpValues, fpTuples)
          else
            val intValues = rawRow.map(parseHexBigInt)
            val sew = sewOpt.getOrElse(Sew.Sew8)
            val perElement = intValues.map(v => ValuePred.classify(v, sew))
            val tuples     = TuplePred.classify(intValues, sew, hint)
            ClassifiedRow(rawRow, perElement, tuples, Nil, Nil)
        if isLitOnly(row) then litOnly += 1
        row
      }
      rowsBuilder += (key -> classified)

    TomlSnapshot(spec, rowsBuilder.result(), litOnly)

  private def parseHexBigInt(token: String): BigInt =
    val t = token.trim.stripPrefix("\"").stripSuffix("\"").trim
    if t.startsWith("0x") || t.startsWith("0X") then BigInt(t.drop(2), 16)
    else if t.matches("^-?\\d+$") then BigInt(t)
    else BigInt(0)

  private def sewFor(key: String): Option[Sew] = key match
    case "sew8"  => Some(Sew.Sew8)
    case "sew16" => Some(Sew.Sew16)
    case "sew32" => Some(Sew.Sew32)
    case "sew64" => Some(Sew.Sew64)
    case _       => None

  private def isFpKey(key: String): Boolean =
    key.startsWith("fsew") || key.startsWith("bf16sew")

  private def hintFor(name: String): ClassifyHint =
    val n = name.toLowerCase
    if n.startsWith("vadd") || n.startsWith("vsub") || n.startsWith("vrsub") ||
      n.startsWith("vsadd") || n.startsWith("vssub") || n.startsWith("vaadd") ||
      n.startsWith("vasub") || n.startsWith("vwadd") || n.startsWith("vwsub")
    then ClassifyHint.Add
    else if n.startsWith("vdiv") || n.startsWith("vrem") then ClassifyHint.Divide
    else if n.startsWith("vsll") || n.startsWith("vsrl") || n.startsWith("vsra") ||
      n.startsWith("vnsrl") || n.startsWith("vnsra") || n.startsWith("vssrl") ||
      n.startsWith("vssra") || n.startsWith("vnclip") || n.startsWith("vrol") ||
      n.startsWith("vror") || n.startsWith("vwsll")
    then ClassifyHint.Shift
    else ClassifyHint.Generic

  // ---------- Serialization ----------

  def renderSnapshotJson(snap: TomlSnapshot): String =
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append(s"""  "extension": "${snap.spec.extension}",\n""")
    sb.append(s"""  "name": "${snap.spec.name}",\n""")
    sb.append(s"""  "format": "${snap.spec.format}",\n""")
    sb.append(s"""  "vxrm": ${snap.spec.vxrm},\n""")
    sb.append(s"""  "vxsat": ${snap.spec.vxsat},\n""")
    sb.append(s"""  "notestfloat3": ${snap.spec.notestfloat3},\n""")
    sb.append(s"""  "contentHash": "${snap.spec.contentHash}",\n""")
    sb.append(s"""  "litOnlyCount": ${snap.litOnlyCount},\n""")
    sb.append("""  "tests": {""").append("\n")
    val sortedKeys = snap.rowsByKey.keys.toList.sorted
    sortedKeys.zipWithIndex.foreach { case (key, i) =>
      sb.append(s"""    "$key": [""").append("\n")
      val rows = snap.rowsByKey(key)
      rows.zipWithIndex.foreach { case (row, j) =>
        sb.append("      {").append("\n")
        sb.append(s"""        "raw": ${jsonArrayOfStrings(row.rawRow)},""").append("\n")
        sb.append(s"""        "valuePreds": ${jsonNestedPreds(row.valuePreds)},""").append("\n")
        sb.append(s"""        "tuplePreds": ${jsonListOfTups(row.tuplePreds)},""").append("\n")
        sb.append(s"""        "fpValuePreds": ${jsonNestedFpPreds(row.fpValuePreds)},""").append("\n")
        sb.append(s"""        "fpTuplePreds": ${jsonListOfFpTups(row.fpTuplePreds)}""").append("\n")
        sb.append("      }").append(if j < rows.size - 1 then ",\n" else "\n")
      }
      sb.append("    ]").append(if i < sortedKeys.size - 1 then ",\n" else "\n")
    }
    sb.append("  }\n").append("}\n")
    sb.toString

  private def jsonArrayOfStrings(xs: List[String]): String =
    xs.map(s => s""""${s.replace("\\", "\\\\").replace("\"", "\\\"")}"""").mkString("[", ",", "]")

  private def jsonNestedPreds(xs: List[List[ValuePred]]): String =
    xs.map(_.map(p => s""""${p}"""").mkString("[", ",", "]")).mkString("[", ",", "]")

  private def jsonListOfTups(xs: List[TuplePred]): String =
    xs.map(p => s""""${p}"""").mkString("[", ",", "]")

  private def jsonNestedFpPreds(xs: List[List[FpValuePred]]): String =
    xs.map(_.map(p => s""""${p}"""").mkString("[", ",", "]")).mkString("[", ",", "]")

  private def jsonListOfFpTups(xs: List[FpTuplePred]): String =
    xs.map(p => s""""${p}"""").mkString("[", ",", "]")

  // ---------- Driver entry points ----------

  /** Result of walking the upstream configs/ tree: successfully parsed
   *  specs and any parse errors. Round-4 review (Codex) flagged that the
   *  prior version silently dropped failures.
   */
  final case class WalkResult(specs: List[TomlSpec], errors: List[(Path, String)])

  def walkConfigs(configsRoot: Path): WalkResult =
    import scala.jdk.CollectionConverters.*
    if !Files.isDirectory(configsRoot) then WalkResult(Nil, Nil)
    else
      val specs  = List.newBuilder[TomlSpec]
      val errors = List.newBuilder[(Path, String)]
      Files.walk(configsRoot).iterator.asScala.foreach { p =>
        if Files.isRegularFile(p) && p.getFileName.toString.endsWith(".toml") then
          val ext = p.getParent.getFileName.toString
          parse(ext, p) match
            case Right(spec) => specs += spec
            case Left(msg)   => errors += (p -> msg)
      }
      WalkResult(specs.result(), errors.result())

  def renderForwardReport(snaps: List[TomlSnapshot]): String =
    val sb        = new StringBuilder
    val nTomls    = snaps.size
    val nRows     = snaps.flatMap(_.rowsByKey.values).flatten.size
    val nLitTomls = snaps.count(_.litOnlyCount > 0)
    val nLitRows  = snaps.map(_.litOnlyCount).sum
    // Cell- and tuple-level Lit counts (a row may contain ValuePred.Lit
    // at the element level while still having a non-Lit TuplePred at the
    // row level; both metrics matter for understanding vocabulary cover).
    val nValueLitCells = snaps.flatMap(_.rowsByKey.values).flatten
      .flatMap(_.valuePreds).flatten.count(_.isInstanceOf[ValuePred.Lit])
    val nTupleLitRows  = snaps.flatMap(_.rowsByKey.values).flatten
      .count(_.tuplePreds.exists(_.isInstanceOf[TuplePred.Lit]))
    val nFpLitCells    = snaps.flatMap(_.rowsByKey.values).flatten
      .flatMap(_.fpValuePreds).flatten.count(_.isInstanceOf[FpValuePred.FpLit])
    val nFpTupleLits   = snaps.flatMap(_.rowsByKey.values).flatten
      .count(_.fpTuplePreds.exists(_.isInstanceOf[FpTuplePred.Lit]))
    sb.append("# Forward Audit Report (AC-3)\n\n")
    sb.append("Generated from upstream `riscv-vector-tests/configs/`. ")
    sb.append("Per AC-3, `Lit(BigInt, rationale)` and `FpLit(literal, rationale)` are ")
    sb.append("valid classifications. The metrics below are *informational*: they show ")
    sb.append("how much curator intent the named vocabulary captures vs. how much falls ")
    sb.append("through to Lit. Unclassified-and-unwaived count is 0 by construction.\n\n")
    sb.append(s"- Tomls scanned: $nTomls\n")
    sb.append(s"- Total literal rows: $nRows\n")
    sb.append(s"- Rows where every classification is Lit (`litOnlyCount`): $nLitRows ")
    sb.append(s"across $nLitTomls tomls\n")
    sb.append(s"- Element cells classified as `ValuePred.Lit`: $nValueLitCells\n")
    sb.append(s"- Element cells classified as `FpValuePred.FpLit`: $nFpLitCells\n")
    sb.append(s"- Rows with at least one `TuplePred.Lit`: $nTupleLitRows\n")
    sb.append(s"- Rows with at least one `FpTuplePred.Lit`: $nFpTupleLits\n\n")
    if nLitRows > 0 then
      sb.append("## Per-toml lit-only row count (descending)\n\n")
      sb.append("| Toml | lit-only rows |\n|---|---|\n")
      snaps.filter(_.litOnlyCount > 0).sortBy(-_.litOnlyCount).foreach { s =>
        sb.append(s"| ${s.spec.extension}/${s.spec.name} | ${s.litOnlyCount} |\n")
      }
    sb.toString

  def renderBackwardReport(snaps: List[TomlSnapshot]): String =
    val coverage = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    for snap <- snaps
        rows <- snap.rowsByKey.values
        row  <- rows
    do
      row.valuePreds.flatten.foreach(p => coverage(predName(p)) += 1)
      row.tuplePreds.foreach(p => coverage(predName(p)) += 1)
      row.fpValuePreds.flatten.foreach(p => coverage(predName(p)) += 1)
      row.fpTuplePreds.foreach(p => coverage(predName(p)) += 1)

    val allPredNames = (ValuePred.caseNames ++ TuplePred.caseNames ++
      FpValuePred.caseNames ++ FpTuplePred.caseNames).toSet
    // Per AC-4, escape-hatch and runtime-only predicates are excluded from
    // the dead-vocabulary check: Lit / FpLit (acceptable per DEC-1), Random
    // (only produced by runtime emission, never by offline audit).
    val excluded     = Set("Lit", "FpLit", "Random")
    val dead         = (allPredNames -- excluded).diff(coverage.keySet)

    val sb = new StringBuilder
    sb.append("# Backward Audit Report (AC-4)\n\n")
    sb.append("Each predicate's hit count across upstream tomls. AC-4 requires every ")
    sb.append("non-`Lit`, non-`Random` (and non-`FpLit`) predicate to be justified by ")
    sb.append("at least one upstream literal-tuple.\n\n")
    sb.append("## Hit counts (descending)\n\n")
    sb.append("| Predicate | Hits |\n|---|---|\n")
    coverage.toList.sortBy(-_._2).foreach { case (name, n) =>
      sb.append(s"| `$name` | $n |\n")
    }
    sb.append(s"\n## Dead vocabulary (zero hits, excluding escape hatches): ${dead.size}\n\n")
    if dead.nonEmpty then
      sb.append("These predicates violate AC-4 and should either be removed or backed by an upstream literal.\n\n")
      dead.toList.sorted.foreach(d => sb.append(s"- `$d`\n"))
    else sb.append("All named predicates are exercised by upstream literals.\n")
    sb.toString

  /** Name a predicate by stripping any parameter list from its `toString`.
   *  Scala 3 singleton enum cases return `""` from `getClass.getSimpleName`
   *  (their runtime class is an anonymous module), so we cannot rely on
   *  reflection. `toString` gives `"Zero"` for singleton cases and
   *  `"MaxSigned(Sew8)"` for parameterized cases; the substring before
   *  the first `(` is the case name.
   */
  private def predName(p: Any): String =
    val s   = p.toString
    val idx = s.indexOf('(')
    if idx < 0 then s else s.substring(0, idx)

  @main def writeAuditFixtures(outDir: String): Unit =
    val cwd          = Paths.get(System.getProperty("user.dir"))
    val configsCand  = sys.env.get("RVPROBE_RVV_TESTS_CONFIGS").map(Paths.get(_)).orElse {
      LazyList.iterate(cwd: Path)(_.getParent).takeWhile(_ != null)
        .find(p => Files.exists(p.resolve("riscv-vector-tests/configs")))
        .map(_.resolve("riscv-vector-tests/configs"))
    }
    val configsRoot = configsCand.getOrElse {
      throw new IllegalStateException("upstream riscv-vector-tests/configs not found")
    }
    val walkResult = walkConfigs(configsRoot)
    if walkResult.errors.nonEmpty then
      val msg = walkResult.errors.map { case (p, e) => s"  $p: $e" }.mkString("\n")
      throw new IllegalStateException(s"${walkResult.errors.size} parse failures:\n$msg")
    if walkResult.specs.size != 676 then
      throw new IllegalStateException(
        s"expected 676 tomls under $configsRoot, parsed ${walkResult.specs.size} " +
          "(if upstream added or removed tomls, update this expected count)")
    val snaps = walkResult.specs.map(classify)
    val out   = Paths.get(outDir)
    Files.createDirectories(out)
    val snapDir = out.resolve("snapshots")
    Files.createDirectories(snapDir)
    snaps.foreach { s =>
      val extDir = snapDir.resolve(s.spec.extension)
      Files.createDirectories(extDir)
      val file = extDir.resolve(s"${s.spec.name}.json")
      Files.write(file, renderSnapshotJson(s).getBytes(StandardCharsets.UTF_8))
    }
    Files.write(out.resolve("forward-report.md"), renderForwardReport(snaps).getBytes(StandardCharsets.UTF_8))
    Files.write(out.resolve("backward-report.md"), renderBackwardReport(snaps).getBytes(StandardCharsets.UTF_8))
    println(s"wrote ${snaps.size} snapshots + forward/backward reports under $out")
    val totalLitOnly = snaps.map(_.litOnlyCount).sum
    println(s"Lit-only rows: $totalLitOnly across ${snaps.count(_.litOnlyCount > 0)} tomls")
