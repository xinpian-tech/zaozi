// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.plugin

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.util.Spans.*

import scala.collection.mutable
import scala.util.control.NonFatal

/** Presentation-compiler phase that makes go-to-definition and hover on a zaozi dynamic bundle-field access resolve to
  * the real field declaration.
  *
  * A `Referable[T]` or `Interface[T]` (`scala.Dynamic`) access `io.a` is a `transparent inline selectDynamic("a")`
  * whose expansion drops the field name to a runtime string; the retained pre-inlining call `io.selectDynamic("a")`
  * carries only the framework method symbol, so the compiler resolves `io.a` to `selectDynamic` rather than `val a`.
  * This phase runs after `typer` (the inline expansion already happened there) and replaces the `Inlined` node of the
  * access with an ascription around a `Select` on the resolved field symbol, positioned at the access span. The
  * navigation target (the select, whose symbol is the field) is deliberately separate from the expression's semantic
  * type: the ascription keeps the access's own type (`Ref[T]`, or `Option[Ref[T]]`), so type-driven operations such as
  * completion on `io.a.getType` keep seeing the dynamic access's result instead of the field's `BundleField[T]`.
  * Rewriting only `Inlined.call` is not enough for span-based navigation: the expansion's synthetic receiver val spans
  * the whole access and wins symbol-at-cursor, while the retained `call` is not walked at all. Nested accesses
  * (`io.a.b`) are rebuilt level by level so the inner level keeps its own node, and the inliner's receiver proxies
  * (`val Referable_this = io`), which live on in the enclosing `Block`, are collapsed to point spans for the same
  * reason.
  *
  * It is contributed by [[ZaoziSemanticDBPlugin.initialize]] ONLY to interactive presentation-compiler pipelines
  * (parser/typer/SetRootTree/cookComments): the rewrite mutates the typed tree, so in the batch pipeline it would be
  * pickled into published TASTy — batch compilations get the SemanticDB enhancer phase instead and this phase is never
  * scheduled there. It keys strictly on the zaozi API (receiver derives from `me.jiuyang.zaozi.reftpe.Referable` or
  * `me.jiuyang.zaozi.reftpe.Interface`, whose bundle type argument is a `me.jiuyang.zaozi.magic.DynamicSubfield`), so
  * it is inert on foreign `scala.Dynamic` code, and every step is guarded so it can never fail an interactive request.
  */
class ZaoziPcNavPhase extends PluginPhase:
  import ZaoziPcNavPhase.*

  override val phaseName: String = ZaoziPcNavPhase.name

  // Anchor on phases the interactive presentation compiler actually schedules (it runs only
  // parser, typer, SetRootTree, cookComments); posttyper/inlining are absent there and would
  // mis-schedule this phase.
  override val runsAfter:  Set[String] = Set("typer")
  override val runsBefore: Set[String] = Set("SetRootTree")

  /** Spans (start, end) of the dynamic accesses this run rewrote. A receiver proxy is only neutralized when its span is
    * one of them, so the rewrite never touches anything unrelated to a rewritten access.
    */
  private val rewrittenAccessSpans = mutable.Set.empty[(Int, Int)]

  override def transformInlined(
    tree: Inlined
  )(
    using Context
  ): Tree =
    try
      dynamicSelect(tree.call, tree.tpe) match
        case Some(nav) =>
          // The primary path: this Inlined IS the dynamic access `io.a`. Drop the whole node — the
          // expansion's synthetic vals must not remain as competing symbol-at-cursor candidates.
          nav
        case None      =>
          // Not itself a dynamic access, but its retained `call` (e.g. the whole `Tests { ... }`
          // argument of an enclosing utest/macro `Inlined`) may hold typed COPIES of dynamic
          // accesses that navigation reaches; an un-rewritten copy there makes go-to land on the
          // stale `selectDynamic`. Rewrite those copies too.
          val call1 = rewriteDynamics(tree.call)
          if call1 eq tree.call then tree
          else cpy.Inlined(tree)(call1, tree.bindings, tree.expansion)
    catch case NonFatal(_) => tree

  override def transformBlock(
    tree: Block
  )(
    using Context
  ): Tree =
    val tree1 = transformOther(tree)
    tree1 match
      case block @ Block(stats, expr) =>
        val stats1 = stats.map(neutralizeInlineProxy)
        if stats1 == stats then block else cpy.Block(block)(stats1, expr)
      case other                      => other

  /** Collapse a compiler-generated inline-receiver proxy (`val Referable_this = io`) to a point span. The binding must
    * stay — the rebuilt selects reference it — but its span covers the whole access it duplicates, so it would win
    * symbol-at-cursor over the field select. Nested accesses keep theirs in the enclosing `Block`; the ones bound
    * inside the `Inlined` disappear with the node this phase drops.
    */
  private def neutralizeInlineProxy(
    t: Tree
  )(
    using Context
  ): Tree =
    t match
      case vd: ValDef if isInlineProxy(vd) => vd.withSpan(Span(vd.span.start))
      case _ => t

  /** A compiler-generated inline receiver proxy, i.e. the `val Referable_this = io` the inliner binds around a dynamic
    * access. The checks are deliberately strict: only synthetic vals (never a user-written `_this` val, whose span and
    * children must stay navigable), and only when the span is one of the accesses this run actually rewrote.
    */
  /** A compiler-generated inline receiver proxy, i.e. the `val Referable_this = io` the inliner binds around a dynamic
    * access. On this compiler those vals carry no `Synthetic` flag, so the identity is pinned down structurally: the
    * exact generated name, a span that is one of the accesses this run rewrote, and the receiver/field-select shape. A
    * user-written `_this` val keeps the span of its own declaration, never that of a rewritten access.
    */
  private def isInlineProxy(
    vd: ValDef
  )(
    using Context
  ): Boolean =
    ProxyNames.contains(vd.symbol.name.toString) &&
      rewrittenAccessSpans.contains((vd.span.start, vd.span.end)) && (
        isFieldSelect(vd.rhs) || vd.rhs.isInstanceOf[Inlined] ||
          (vd.tpt.tpe.exists && bundleOf(vd.tpt.tpe.widen).isDefined)
      )

  /** `Select` on a bundle-field symbol, i.e. the node this phase builds for an access. */
  private def isFieldSelect(
    t: Tree
  )(
    using Context
  ): Boolean =
    t match
      case Select(_, _) => isBundleField(t.symbol)
      case _            => false

  private def isBundleField(
    sym: Symbol
  )(
    using Context
  ): Boolean =
    sym.exists && sym.isTerm && sym.owner.isClass && isDynamicSubfield(sym.owner.typeRef)

  /** The navigation node for the dynamic field access in `call`: the innermost `Select` carries the field symbol as the
    * navigation target, while the `Typed` wrapper keeps the access's semantic type (`accessTpe`), so the two concerns
    * stay separate. The receiver chain is rewritten the same way, so `io.a.b` gets one node per level (the inner level
    * must not lose its node to the outer one).
    */
  private def dynamicSelect(
    call:      Tree,
    accessTpe: Type
  )(
    using Context
  ): Option[Tree] =
    bundleFieldAccess(call).flatMap { (qual, bundleType, fieldName) =>
      resolveField(bundleType, fieldName).map { sym =>
        if call.span.exists then rewrittenAccessSpans += ((call.span.start, call.span.end))
        val target = Select(rewriteDynamics(qual), sym.termRef).withSpan(call.span)
        val nav    = Typed(target, TypeTree(accessTpe)).withSpan(call.span)
        if accessTpe.exists then nav.withType(accessTpe) else nav
      }
    }

  /** Rewrite dynamic-access `Inlined` copies nested anywhere inside `tree`, including retained inline/macro calls and
    * receiver chains (which a plain `TreeMap`/megaphase traversal does not descend into).
    */
  private def rewriteDynamics(
    tree: Tree
  )(
    using Context
  ): Tree =
    if tree.isEmpty then tree
    else
      val mapper = new TreeMap:
        override def transform(
          t: Tree
        )(
          using Context
        ): Tree = t match
          case inl: Inlined =>
            dynamicSelect(inl.call, inl.tpe).getOrElse(
              cpy.Inlined(inl)(transform(inl.call), transformSub(inl.bindings), transform(inl.expansion))
            )
          case _ => super.transform(t)
      mapper.transform(tree)

  /** `(receiver, bundleType, fieldName)` of a zaozi dynamic field access, from the retained pre-inlining call. Two
    * selector shapes are recognized, both on a `Referable[T]` or `Interface[T]` receiver whose type argument `T` is the
    * bundle:
    *   - `qual.selectDynamic("field")` — the retained call for `io.field` (the primary path).
    *   - `qual.subRef("field")` / `qual.subRefOption("field")` — the macro-expanded accessor, if it is ever the
    *     retained call (defensive).
    *
    * `applyDynamic`/`applyDynamicNamed` (index/slice) are intentionally NOT matched, so `io.vec(i)`/`io.bits(hi, lo)`
    * stay as identity.
    */
  private def bundleFieldAccess(
    call: Tree
  )(
    using Context
  ): Option[(Tree, Type, String)] =
    call match
      case Apply(Select(qual, sel), List(Literal(Constant(field: String)))) if isFieldSelector(sel.toString) =>
        bundleOf(qual.tpe.widen).map(t => (qual, t, field))
      case _                                                                                                 => None

  private def isFieldSelector(name: String): Boolean =
    name == "selectDynamic" || name == "subRef" || name == "subRefOption"

  /** `T` of a `Referable[T]` or `Interface[T]` receiver, when `T <: DynamicSubfield`. */
  private def bundleOf(
    tpe: Type
  )(
    using Context
  ): Option[Type] =
    def argOf(className: String) =
      tpe.baseClasses.find(_.fullName.toString == className).flatMap(cls => tpe.baseType(cls).argInfos.headOption)
    argOf(ReferableName).orElse(argOf(InterfaceName)).filter(isDynamicSubfield)

  private def isDynamicSubfield(
    tpe: Type
  )(
    using Context
  ): Boolean =
    tpe.baseClasses.exists(_.fullName.toString == DynamicSubfieldName)

  /** Resolve `field` to a real, non-synthetic term member of the bundle type. */
  private def resolveField(
    bundleType: Type,
    fieldName:  String
  )(
    using Context
  ): Option[Symbol] =
    val sym = bundleType.member(termName(fieldName)).symbol
    Option.when(sym.exists && sym.isTerm && !sym.is(Flags.Synthetic))(sym)

object ZaoziPcNavPhase:
  val name: String = "zaoziPcNav"

  /** The phases this phase orders itself against; the plugin only contributes it to pipelines that contain all of them
    * and NO `posttyper` (see [[ZaoziSemanticDBPlugin.initialize]]).
    */
  val anchors: Set[String] = Set("typer", "SetRootTree")

  private val ReferableName       = "me.jiuyang.zaozi.reftpe.Referable"
  private val InterfaceName       = "me.jiuyang.zaozi.reftpe.Interface"
  private val DynamicSubfieldName = "me.jiuyang.zaozi.magic.DynamicSubfield"

  /** Compiler-generated receiver-proxy names, one per zaozi receiver base. */
  private val ProxyNames = Set("Referable_this", "Interface_this")
