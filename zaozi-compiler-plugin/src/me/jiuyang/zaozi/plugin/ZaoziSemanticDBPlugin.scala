// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.plugin

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.{ctx, Context}
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.{getClassIfDefined, ClassSymbol, NoSymbol, Symbol}
import dotty.tools.dotc.core.Types.{AppliedType, NoType, Type}
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}
import dotty.tools.dotc.semanticdb.{
  Range as SdbRange,
  SymbolInformation,
  SymbolOccurrence,
  TextDocuments,
  ZaoziSemanticdbFacade
}
import dotty.tools.dotc.util.{SourceFile, Spans}
import dotty.tools.io.JarArchive

import java.nio.file.{Files, Path, Paths}

/** Zaozi SemanticDB enhancement plugin.
  *
  * Zaozi's hardware DSL accesses bundle fields through `scala.Dynamic`: `io.field` is rewritten by the typer to
  * `io.selectDynamic("field")`, which is a transparent inline macro that expands to `getRefViaFieldValName` calls.
  * Vanilla SemanticDB therefore records an occurrence of `Referable#selectDynamic().` at the `field` position instead
  * of the bundle field itself, so SemanticDB-first tooling (scala3-bsp-semantic-ls, Metals, scalafix) cannot resolve
  * go-to-definition or find-references for bundle fields.
  *
  * This [[StandardPlugin]] adds a single phase that runs right after the compiler's own
  * `extractSemanticDBExtractSemanticInfo` phase (which writes the `.semanticdb` file for every compilation unit of the
  * run *before* the next phase group starts) and before `posttyper` (so the tree still has exactly the shape the
  * extractor saw). For each compilation unit it rewrites the dynamic-method occurrences to the SemanticDB symbol of the
  * accessed bundle field `val`, using the compiler's own symbol naming so the occurrences group with the definitions
  * the extractor already emitted for the defining `val`s.
  *
  * Design notes (deliberate differences from the earlier ResearchPlugin prototype, zaozi PR #91):
  *   - Standard plugin on the stable compiler; no `-experimental`, no nightly pin.
  *   - No global registry: all state lives in the single `run` invocation for the current unit, so long-lived zinc/mill
  *     workers and incremental compilation cannot observe stale state.
  *   - The field symbol is synthesized from the *receiver's type* (bundle class symbol -> member lookup), never from
  *     same-run definition sites, so a usage file is enhanced correctly even when the defining file is not part of the
  *     (incremental) run.
  *   - Occurrences are rewritten only when both the range and the original dynamic-method symbol match what the tree
  *     proves, keyed per span; same-named fields of unrelated bundles cannot collide.
  *   - `-semanticdb-target` is honored using the same path computation as `ExtractSemanticDB`.
  */
class ZaoziSemanticDBPlugin extends StandardPlugin:
  override val name:        String = "zaozi-semanticdb"
  override val description: String = "enhance SemanticDB with bundle-field occurrences for the zaozi hardware DSL"

  override def initialize(
    options: List[String]
  )(
    using Context
  ): List[PluginPhase] =
    List(ZaoziSemanticDBPhase())

object ZaoziSemanticDBPlugin:
  val phaseName: String = "zaoziSemanticdb"

class ZaoziSemanticDBPhase extends PluginPhase:
  override val phaseName: String = ZaoziSemanticDBPlugin.phaseName

  // `extractSemanticDBExtractSemanticInfo` writes the `.semanticdb` file of every unit in the run
  // during its own `runOn`, i.e. strictly before this phase first runs; anchoring right after it
  // (and before `posttyper`) keeps the tree byte-identical to what the extractor traversed, so all
  // spans collected here line up with the occurrence ranges already in the document.
  override val runsAfter:  Set[String] = Set("extractSemanticDBExtractSemanticInfo")
  override val runsBefore: Set[String] = Set("posttyper")

  override def isRunnable(
    using Context
  ): Boolean =
    // Mirror ExtractSemanticDB.isRunnable: only useful when SemanticDB is being emitted, and the
    // extractor does not write into jar outputs unless -semanticdb-target redirects elsewhere.
    def writesToOutputJar =
      ctx.settings.semanticdbTarget.value.isEmpty && ctx.settings.outputDir.value.isInstanceOf[JarArchive]
    super.isRunnable && ctx.settings.Xsemanticdb.value && !writesToOutputJar

  /** A bundle-field access through `Referable`'s dynamic methods, proven from the typed tree. */
  private case class DynamicFieldUse(fieldSym: Symbol, dynamicSym: Symbol, span: Spans.Span)

  /** A bundle-field `val` defined in the current unit. */
  private case class FieldDef(fieldSym: Symbol, nameSpan: Spans.Span)

  override def run(
    using Context
  ): Unit =
    val referableCls       = getClassIfDefined("me.jiuyang.zaozi.reftpe.Referable")
    val dynamicSubfieldCls = getClassIfDefined("me.jiuyang.zaozi.magic.DynamicSubfield")
    val bundleFieldCls     = getClassIfDefined("me.jiuyang.zaozi.valuetpe.BundleField")
    if !(referableCls.exists && dynamicSubfieldCls.exists && bundleFieldCls.exists) then return

    val unit      = ctx.compilationUnit
    val collector = Collector(referableCls.asClass, dynamicSubfieldCls.asClass, bundleFieldCls.asClass)
    collector.traverse(unit.tpdTree)
    if collector.uses.isEmpty && collector.defs.isEmpty then return

    semanticdbPath(unit.source) match
      case Some(path) if Files.exists(path) => enhance(path, unit.source, collector.defs.toList, collector.uses.toList)
      case _                                => ()
  end run

  /** Collects bundle-field definitions and dynamic bundle-field accesses from the typed tree.
    *
    * Mirrors `ExtractSemanticDB.Extractor`'s traversal decision for inlined code: only the `call` part of an `Inlined`
    * node is visited (the extractor never emits occurrences from expansions, so there is nothing to rewrite there
    * either).
    */
  private final class Collector(referableCls: ClassSymbol, dynamicSubfieldCls: ClassSymbol, bundleFieldCls: ClassSymbol)
      extends TreeTraverser:
    val defs = collection.mutable.ListBuffer.empty[FieldDef]
    val uses = collection.mutable.ListBuffer.empty[DynamicFieldUse]

    override def traverse(
      tree: Tree
    )(
      using Context
    ): Unit =
      tree match
        case tree: Inlined =>
          collectDynamicCall(tree.call)
          traverse(tree.call)
        case tree @ TypeDef(_, template: Template)
            if tree.symbol.isClass && tree.symbol.derivesFrom(dynamicSubfieldCls) =>
          template.body.foreach {
            case vd: ValDef
                if vd.symbol.exists && !vd.symbol.is(dotty.tools.dotc.core.Flags.Synthetic) &&
                  isBundleFieldType(vd.symbol.info) && vd.nameSpan.exists && !vd.nameSpan.isZeroExtent =>
              defs += FieldDef(vd.symbol, vd.nameSpan)
            case _ => ()
          }
          traverseChildren(tree)
        case _ =>
          traverseChildren(tree)

    /** `BundleField[?]` or `Option[BundleField[?]]`: the two shapes `selectDynamic` resolves. */
    private def isBundleFieldType(
      tp: Type
    )(
      using Context
    ): Boolean =
      val t = tp.widenDealias
      t.derivesFrom(bundleFieldCls) ||
      (t.derivesFrom(dotty.tools.dotc.core.Symbols.defn.OptionClass) && t.argInfos.exists(
        _.derivesFrom(bundleFieldCls)
      ))

    /** Match the retained call of an inlined `Referable` dynamic application: `recv.selectDynamic("field")`,
      * `recv.applyDynamic("field")(args*)`, `recv.applyDynamicNamed("field")(args*)`, with or without type arguments.
      */
    private def collectDynamicCall(
      call: Tree
    )(
      using Context
    ): Unit =
      def unwrap(t: Tree): Option[(Select, Tree, String)] = t match
        case Apply(fun, args) =>
          fun match
            case sel @ Select(recv, _) if isReferableDynamic(sel.symbol)               =>
              args match
                case Literal(Constant(fieldName: String)) :: _ => Some((sel, recv, fieldName))
                case _                                         => None
            case TypeApply(sel @ Select(recv, _), _) if isReferableDynamic(sel.symbol) =>
              args match
                case Literal(Constant(fieldName: String)) :: _ => Some((sel, recv, fieldName))
                case _                                         => None
            case fun: Apply => unwrap(fun)
            case _                                                                     => None
        case _                => None

      unwrap(call).foreach { case (sel, recv, fieldName) =>
        val nameSpan = sel.nameSpan
        if nameSpan.exists && !nameSpan.isZeroExtent then
          val fieldSym = fieldSymbolFor(recv, fieldName)
          if fieldSym.exists then uses += DynamicFieldUse(fieldSym, sel.symbol, nameSpan)
      }

    private def isReferableDynamic(
      sym: Symbol
    )(
      using Context
    ): Boolean =
      sym.exists && sym.owner == referableCls &&
        (sym.name == nme.selectDynamic || sym.name == nme.applyDynamic || sym.name == nme.applyDynamicNamed)

    /** Resolve the accessed field `val` from the receiver's type: `Referable[T]` gives the bundle type `T`; the field
      * is its member with the accessed name and a `BundleField` type. Purely type-derived, so it works for bundles
      * defined in other files or upstream jars.
      */
    private def fieldSymbolFor(
      recv:      Tree,
      fieldName: String
    )(
      using Context
    ): Symbol =
      recv.tpe.widenDealias.baseType(referableCls) match
        case AppliedType(_, bundleTpe :: Nil) =>
          val member = bundleTpe.member(termName(fieldName)).symbol
          if member.exists && isBundleFieldType(member.info) then member else NoSymbol
        case _                                => NoSymbol
  end Collector

  /** The `.semanticdb` path for `source`, computed exactly as `ExtractSemanticDB` does, honoring `-semanticdb-target`
    * and falling back to the class output directory.
    */
  private def semanticdbPath(
    source: SourceFile
  )(
    using Context
  ): Option[Path] =
    val targetSetting = ctx.settings.semanticdbTarget.value
    val base: Option[Path] =
      if targetSetting.nonEmpty then Some(Paths.get(targetSetting))
      else
        ctx.settings.outputDir.value match
          case _: JarArchive => None
          case outputDir => Option(outputDir.jpath)
    base.map { base =>
      base.toAbsolutePath.normalize
        .resolve("META-INF")
        .resolve("semanticdb")
        .resolve(SourceFile.relativePath(source, ctx.settings.sourceroot.value))
        .resolveSibling(source.name + ".semanticdb")
    }

  private def enhance(
    path:   Path,
    source: SourceFile,
    defs:   List[FieldDef],
    uses:   List[DynamicFieldUse]
  )(
    using Context
  ): Unit =
    val facade = ZaoziSemanticdbFacade()

    // For every proven dynamic access: at this exact range, an occurrence of this exact dynamic
    // method symbol must be rewritten to this exact field symbol. Local bundle classes get
    // unreproducible `localN` symbols, so they are skipped.
    case class Rewrite(dynamicSymbol: String, fieldSymbol: String)
    val rewrites: Map[SdbRange, Rewrite] =
      uses.flatMap { use =>
        if !facade.isGlobal(use.fieldSym) then None
        else
          facade
            .range(use.span, source)
            .map(_ -> Rewrite(facade.symbolName(use.dynamicSym), facade.symbolName(use.fieldSym)))
      }.toMap

    // Definition-side records for fields defined in this unit. The extractor already emits both the
    // DEFINITION occurrence and the SymbolInformation for these plain `val`s; this only repairs the
    // document if a future compiler change stops doing so, and never duplicates existing records.
    case class Definition(range: SdbRange, fieldSym: Symbol, fieldSymbol: String)
    val definitions: List[Definition] =
      defs.flatMap { d =>
        if !facade.isGlobal(d.fieldSym) then None
        else facade.range(d.nameSpan, source).map(Definition(_, d.fieldSym, facade.symbolName(d.fieldSym)))
      }

    if rewrites.isEmpty && definitions.isEmpty then return

    val docs    = TextDocuments.parseFrom(Files.readAllBytes(path))
    var changed = false
    val updated = docs.documents.map { doc =>
      val occurrences = doc.occurrences.map { occ =>
        occ.range.flatMap(rewrites.get) match
          case Some(rewrite) if occ.symbol == rewrite.dynamicSymbol && occ.role == SymbolOccurrence.Role.REFERENCE =>
            changed = true
            occ.copy(symbol = rewrite.fieldSymbol)
          case _                                                                                                   => occ
      }

      val present      = occurrences.map(occ => (occ.range, occ.symbol, occ.role)).toSet
      val presentInfos = doc.symbols.iterator.map(_.symbol).toSet

      val missingUses = rewrites.collect {
        case (range, rewrite)
            if !present((Some(range), rewrite.fieldSymbol, SymbolOccurrence.Role.REFERENCE)) &&
              !present((Some(range), rewrite.dynamicSymbol, SymbolOccurrence.Role.REFERENCE)) =>
          SymbolOccurrence(Some(range), rewrite.fieldSymbol, SymbolOccurrence.Role.REFERENCE)
      }.toList

      val missingDefs = definitions.collect {
        case d if !present((Some(d.range), d.fieldSymbol, SymbolOccurrence.Role.DEFINITION)) =>
          SymbolOccurrence(Some(d.range), d.fieldSymbol, SymbolOccurrence.Role.DEFINITION)
      }

      val missingInfos = definitions.collect {
        case d if !presentInfos(d.fieldSymbol) => facade.valSymbolInformation(d.fieldSym)
      }.distinctBy(_.symbol)

      if missingUses.nonEmpty || missingDefs.nonEmpty || missingInfos.nonEmpty then changed = true
      doc.copy(
        occurrences = occurrences ++ missingUses ++ missingDefs,
        symbols = doc.symbols ++ missingInfos
      )
    }

    if changed then Files.write(path, TextDocuments(updated).toByteArray)
  end enhance
end ZaoziSemanticDBPhase
