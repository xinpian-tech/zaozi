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

import scala.util.control.NonFatal

/** Presentation-compiler phase that makes go-to-definition and hover on a zaozi dynamic bundle-field access resolve to
  * the real field declaration.
  *
  * A `Referable[T]` or `Interface[T]` (`scala.Dynamic`) access `io.a` is a `transparent inline selectDynamic("a")`
  * whose expansion drops the field name to a runtime string; the retained pre-inlining call `io.selectDynamic("a")`
  * carries only the framework method symbol, so the compiler resolves `io.a` to `selectDynamic` rather than `val a`.
  * This phase runs after `typer` (the inline expansion already happened there) and structurally rewrites the
  * `Inlined.call` to a typed reference to the resolved field symbol, so the interactive symbol-at-cursor lookup returns
  * the field.
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

  override def transformInlined(
    tree: Inlined
  )(
    using Context
  ): Tree =
    try
      fieldRefOf(tree.call) match
        case Some(fieldRef) =>
          // The primary path: this Inlined IS the dynamic access `io.a`. Point its retained call
          // at the field so the symbol-at-cursor lookup returns it.
          cpy.Inlined(tree)(fieldRef, tree.bindings, tree.expansion)
        case None           =>
          // Not itself a dynamic access, but its retained `call` (e.g. the whole `Tests { ... }`
          // argument of an enclosing utest/macro `Inlined`) may hold typed COPIES of dynamic
          // accesses. The megaphase never descends into `Inlined.call`, but interactive
          // navigation (`NavigateAST.pathTo` via `productIterator`) does — and prefers the call
          // over the expansion on span ties — so an un-rewritten copy there makes go-to land on
          // the stale `selectDynamic`. Rewrite those copies too.
          val call1 = rewriteRetainedCall(tree.call)
          if call1 eq tree.call then tree
          else cpy.Inlined(tree)(call1, tree.bindings, tree.expansion)
    catch case NonFatal(_) => tree

  /** A typed `ref` to the resolved bundle field if `call` is a zaozi dynamic field access, positioned at the access (so
    * the cursor lands on the field name); else None.
    */
  private def fieldRefOf(
    call: Tree
  )(
    using Context
  ): Option[Tree] =
    bundleFieldAccess(call).flatMap { (bundleType, fieldName) =>
      resolveField(bundleType, fieldName).map(sym => ref(sym).withSpan(call.span))
    }

  /** Rewrite dynamic-access `Inlined` copies nested anywhere inside a retained inline/macro call, including the calls
    * of further-nested `Inlined` nodes (which a plain `TreeMap` does not descend into).
    */
  private def rewriteRetainedCall(
    call: Tree
  )(
    using Context
  ): Tree =
    if call.isEmpty then call
    else
      val mapper = new TreeMap:
        override def transform(
          t: Tree
        )(
          using Context
        ): Tree = t match
          case inl: Inlined =>
            val innerCall = fieldRefOf(inl.call).getOrElse(transform(inl.call))
            cpy.Inlined(inl)(innerCall, transformSub(inl.bindings), transform(inl.expansion))
          case _ => super.transform(t)
      mapper.transform(call)

  /** `(bundleType, fieldName)` of a zaozi dynamic field access, from the retained pre-inlining call. Two selector
    * shapes are recognized, both on a `Referable[T]` or `Interface[T]` receiver whose type argument `T` is the bundle:
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
  ): Option[(Type, String)] =
    call match
      case Apply(Select(qual, sel), List(Literal(Constant(field: String)))) if isFieldSelector(sel.toString) =>
        bundleOf(qual.tpe.widen).map(t => (t, field))
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
