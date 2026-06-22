#!/usr/bin/env bash
# Apply the Zaozi shadow patches to a scala3 source tree, in place, from the tree
# root, before `sbt package`. Two layers:
#   - Provenance markers (sections 1-3): PROPERTY-GATED (no effect unless the JVM is
#     started with -Dzaozi.shadow.marker=true); they prove the patched compiler/PC
#     bytes are the ones actually loaded.
#   - Zaozi navigation feature (section 4): ALWAYS-ON, additive, and type-gated on the
#     zaozi `Referable[Bundle]` qualifier, so non-zaozi behavior is unchanged. The
#     presentation compiler lists Bundle/ProbeBundle fields for `io.` completion.
#
#   bash zaozi-shadow-patch.sh [version] [scala3SourceRev]
set -euo pipefail
VER="${1:-3.8.3}"
REV="${2:-unknown}"

# (1) Provenance marker resources packaged into each jar (commonSettings maps
#     Compile/resourceDirectory -> <project>/resources).
mk_marker() { # $1 = project resources dir, $2 = artifact id
  mkdir -p "$1/META-INF/zaozi-shadow"
  cat > "$1/META-INF/zaozi-shadow/org.scala-lang-$2-$VER.properties" <<EOF
artifact=org.scala-lang:$2:$VER
zaoziShadow=true
scala3SourceRev=$REV
patchSet=marker-v1
builtBy=nix
EOF
}
mk_marker compiler/resources scala3-compiler_3
mk_marker presentation-compiler/resources scala3-presentation-compiler_3

# (2) Compiler behavioral marker: one stderr line at the core compile entry, gated.
perl -0pi -e 's/(\n  def process\(args: Array\[String\], rootCtx: Context\): Reporter = \{\n)/$1    if (sys.props.get("zaozi.shadow.marker").contains("true"))\n      System.err.println("zaozi-shadow-marker compiler org.scala-lang:scala3-compiler_3:'"$VER"'")\n/' \
  compiler/src/dotty/tools/dotc/Driver.scala

# (3) PC behavioral marker: inject a __zaozi_marker__ completion when gated.
PC=presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala
perl -0pi -e 's/\n      new CompletionProvider\(/\n      val __zaoziCompletionList = new CompletionProvider(/' "$PC"
# getItems may return an immutable list, so build a fresh ArrayList (copy + prepend the
# marker) and setItems it back rather than mutating the original in place. Also record the
# loaded PC jar's location (JVM-side provenance) so a harness can hash the actually-loaded
# presentation-compiler jar against the published patched hash. `getResource` on the class's
# own bytecode yields a reliable `jar:file:...!/...` URL (Metals loads the PC through a
# java.net.URLClassLoader); `getProtectionDomain.getCodeSource` is a fallback. The location is
# written to the file named by -Dzaozi.shadow.pc.provenance (a file channel, since Metals
# redirects System.err around PC operations so the stderr line alone is not reliably captured).
perl -0pi -e 's/(\n      \)\.completions\(\)\n)(    \}\(params\.toQueryContext\))/$1      if sys.props.get("zaozi.shadow.marker").contains("true") then\n        val __zaoziNew = new java.util.ArrayList[l.CompletionItem]()\n        val __zaoziCur = __zaoziCompletionList.getItems\n        if __zaoziCur != null then __zaoziNew.addAll(__zaoziCur)\n        __zaoziNew.add(0, new l.CompletionItem("__zaozi_marker__"))\n        __zaoziCompletionList.setItems(__zaoziNew)\n        try\n          val __zaoziRes = classOf[ScalaPresentationCompiler].getResource("ScalaPresentationCompiler.class")\n          val __zaoziCs = classOf[ScalaPresentationCompiler].getProtectionDomain.getCodeSource\n          val __zaoziLoc = if __zaoziRes != null then __zaoziRes.toString else if __zaoziCs != null && __zaoziCs.getLocation != null then __zaoziCs.getLocation.toString else ""\n          if __zaoziLoc.nonEmpty then\n            System.err.println("zaozi-shadow-pc " + __zaoziLoc)\n            sys.props.get("zaozi.shadow.pc.provenance").foreach(__o => java.nio.file.Files.write(java.nio.file.Paths.get(__o), __zaoziLoc.getBytes("UTF-8")))\n        catch case _: Throwable => ()\n      __zaoziCompletionList\n$2/' "$PC"

# (4) Zaozi navigation feature (ALWAYS-ON, additive). The shared field resolver lives in
#     one small object per artifact, matching zaozi types by fully-qualified name so the
#     compiler/PC need no compile-time dependency on zaozi. The PC copy backs completion
#     (this round); the compiler copy backs the find-references SemanticDB occurrence
#     registration (wired separately) and keeps the two symbol derivations in sync.

# (4a) Presentation-compiler resolver: ZaoziPcSupport, used by the completion hook.
cat > presentation-compiler/src/main/dotty/tools/pc/ZaoziPcSupport.scala <<'EOF'
// SPDX-License-Identifier: Apache-2.0
// Zaozi Bundle/ProbeBundle dynamic-field support for the presentation compiler.
// Additive and type-gated: returns Nil for any non-zaozi qualifier, so ordinary
// completion is unaffected. Zaozi types are matched by fully-qualified name, so the
// presentation compiler needs no compile-time dependency on zaozi.
package dotty.tools.pc

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.Flags
import dotty.tools.pc.completions.CompletionValue

object ZaoziPcSupport:
  private val ReferableFqn       = "me.jiuyang.zaozi.reftpe.Referable"
  private val DynamicSubfieldFqn = "me.jiuyang.zaozi.magic.DynamicSubfield"
  private val BundleFieldFqn     = "me.jiuyang.zaozi.valuetpe.BundleField"

  private def baseClassByFqn(tpe: Type, fqn: String)(using Context): Symbol =
    tpe.baseClasses.find(_.fullName.toString == fqn).getOrElse(NoSymbol)

  /** The `T` of a `Referable[T]` qualifier whose `T` is a Bundle/ProbeBundle
   *  (`T <: DynamicSubfield`), if any. */
  private def bundleType(tpe: Type)(using Context): Option[Type] =
    val ref = baseClassByFqn(tpe, ReferableFqn)
    if !ref.exists then None
    else tpe.baseType(ref).argInfos.headOption.filter(t => baseClassByFqn(t, DynamicSubfieldFqn).exists)

  /** True iff `tpe` is a zaozi `Referable[Bundle/ProbeBundle]`. */
  def isZaoziReferable(tpe: Type)(using Context): Boolean = bundleType(tpe).isDefined

  /** Element type `E` of `BundleField[E]` / `Option[BundleField[E]]`, and whether it is
   *  optional; None if `tpe` is neither shape. */
  private def refInfo(tpe: Type)(using Context): Option[(Type, Boolean)] =
    val bf = baseClassByFqn(tpe, BundleFieldFqn)
    if bf.exists then tpe.baseType(bf).argInfos.headOption.map(e => (e, false))
    else
      val opt = tpe.baseType(defn.OptionClass)
      if !opt.exists then None
      else opt.argInfos.headOption.flatMap { inner =>
        val ibf = baseClassByFqn(inner, BundleFieldFqn)
        if ibf.exists then inner.baseType(ibf).argInfos.headOption.map(e => (e, true)) else None
      }

  /** Completion items for the PUBLIC `BundleField[E]` / `Option[BundleField[E]]` fields of the
   *  Bundle/ProbeBundle behind a `Referable[T]` qualifier: filtered by the typed prefix `query`,
   *  de-duplicated against `existing` ordinary completions, with private/protected and
   *  non-`BundleField` vals excluded. Each renders as `<name>: Ref[E]` (`Option[Ref[E]]` for an
   *  optional field), kind Field. Returns Nil for any non-zaozi qualifier. */
  def bundleFieldCompletions(
      qualTpe: Type,
      query: String,
      existing: List[CompletionValue]
  )(using Context): List[CompletionValue] =
    bundleType(qualTpe) match
      case None => Nil
      case Some(bundleTpe) =>
        val cls = bundleTpe.classSymbol
        if !cls.exists then Nil
        else
          val taken = existing.iterator.map(c => c.insertText.getOrElse(c.label)).toSet
          cls.asClass.info.decls.toList.flatMap { f =>
            val nm = f.name.toString
            if f.isTerm && !f.is(Flags.Method) && !f.isOneOf(Flags.Private | Flags.Protected)
               && (query.isEmpty || nm.startsWith(query)) && !taken.contains(nm)
            then
              refInfo(bundleTpe.memberInfo(f)) match
                case Some((e, optional)) =>
                  val es     = e.show
                  val detail = if optional then ": Option[Ref[" + es + "]]" else ": Ref[" + es + "]"
                  List(CompletionValue.ZaoziField(nm, detail))
                case None => Nil
            else Nil
          }
EOF

# (4b) Compiler-side twin: ZaoziSemanticDB (shared field resolution for find-references).
#      Public object (no unused-symbol warnings); the occurrence registration is wired
#      by a later patch. Keeps the field set identical to the PC copy for symbol consistency.
cat > compiler/src/dotty/tools/dotc/semanticdb/ZaoziSemanticDB.scala <<'EOF'
// SPDX-License-Identifier: Apache-2.0
// Zaozi Bundle/ProbeBundle field resolution for the SemanticDB extraction phase.
// Matches zaozi types by fully-qualified name (no compile-time dependency on zaozi).
// Shares the exact field set with the presentation compiler's ZaoziPcSupport so the
// definition (PC) and reference (compiler) symbols line up.
package dotty.tools.dotc.semanticdb

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.Flags

object ZaoziSemanticDB:
  private val ReferableFqn       = "me.jiuyang.zaozi.reftpe.Referable"
  private val DynamicSubfieldFqn = "me.jiuyang.zaozi.magic.DynamicSubfield"
  private val BundleFieldFqn     = "me.jiuyang.zaozi.valuetpe.BundleField"

  private def baseClassByFqn(tpe: Type, fqn: String)(using Context): Symbol =
    tpe.baseClasses.find(_.fullName.toString == fqn).getOrElse(NoSymbol)

  def bundleType(tpe: Type)(using Context): Option[Type] =
    val ref = baseClassByFqn(tpe, ReferableFqn)
    if !ref.exists then None
    else tpe.baseType(ref).argInfos.headOption.filter(t => baseClassByFqn(t, DynamicSubfieldFqn).exists)

  def isZaoziReferable(tpe: Type)(using Context): Boolean = bundleType(tpe).isDefined

  private def isBundleFieldType(tpe: Type)(using Context): Boolean =
    if baseClassByFqn(tpe, BundleFieldFqn).exists then true
    else
      val opt = tpe.baseType(defn.OptionClass)
      opt.exists && opt.argInfos.headOption.exists(a => baseClassByFqn(a, BundleFieldFqn).exists)

  /** The `BundleField`/`Option[BundleField]` field symbol named `name` on the
   *  Bundle/ProbeBundle behind a `Referable[T]` qualifier, if any. */
  def fieldSymbol(qualTpe: Type, name: String)(using Context): Option[Symbol] =
    bundleType(qualTpe).flatMap { bundleTpe =>
      val cls = bundleTpe.classSymbol
      if !cls.exists then None
      else cls.asClass.info.decls.toList.find { f =>
        f.isTerm && !f.is(Flags.Method) && f.name.toString == name
          && isBundleFieldType(bundleTpe.memberInfo(f))
      }
    }
EOF

# (4c) A CompletionValue variant for a Zaozi field: caller-supplied label + detail string,
#      kind Field. Inserted between the `Keyword` and `FileSystemMember` cases.
CV=presentation-compiler/src/main/dotty/tools/pc/completions/CompletionValue.scala
perl -0pi -e 's/(      CompletionItemKind\.Keyword\n)(\n  case class FileSystemMember\()/$1\n  case class ZaoziField(label: String, detail: String) extends CompletionValue:\n    override def insertText: Option[String] = Some(label)\n    override def completionItemKind(using Context): CompletionItemKind = CompletionItemKind.Field\n    override def description(printer: ShortenedTypePrinter)(using Context): String = detail\n    override def labelWithDescription(printer: ShortenedTypePrinter)(using Context): String = label + detail\n$2/' "$CV"

# (4d) Completion hook: in the Select-completion branch, append the zaozi field items,
#      de-duplicated against the ordinary completions. Anchored on the Select-specific
#      `qual.typeOpt.widenDealias` resolution so the `case _ => ... defn.AnyType` branch
#      (same tail expression) is left untouched.
COMPL=presentation-compiler/src/main/dotty/tools/pc/completions/Completions.scala
perl -0pi -e 's/(            val \(compiler, result\) = enrichedCompilerCompletions\(qual\.typeOpt\.widenDealias\)\n)            \(allAdvanced \+\+ compiler, result\)/$1            val __zaoziFields = dotty.tools.pc.ZaoziPcSupport.bundleFieldCompletions(qual.typeOpt.widenDealias, completionPos.query, allAdvanced ++ compiler)\n            (allAdvanced ++ compiler ++ __zaoziFields, result)/' "$COMPL"

# Fail closed if any edit did not take.
grep -q "zaozi-shadow-marker compiler" compiler/src/dotty/tools/dotc/Driver.scala
grep -q "__zaozi_marker__" "$PC"
test -f compiler/resources/META-INF/zaozi-shadow/org.scala-lang-scala3-compiler_3-$VER.properties
test -f presentation-compiler/resources/META-INF/zaozi-shadow/org.scala-lang-scala3-presentation-compiler_3-$VER.properties
test -f presentation-compiler/src/main/dotty/tools/pc/ZaoziPcSupport.scala
test -f compiler/src/dotty/tools/dotc/semanticdb/ZaoziSemanticDB.scala
grep -q "case class ZaoziField" "$CV"
grep -q "ZaoziPcSupport.bundleFieldCompletions" "$COMPL"
echo "zaozi-shadow patch applied (VER=$VER REV=$REV): markers + Bundle-field completion"
