// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.zaozi.magic.macros

import scala.quoted.*

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.BaseGeneratorHelper
import me.jiuyang.zaozi.reftpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block as MlirBlock, Context}

import java.lang.foreign.Arena

/** An instance whose module is chosen after elaboration among architectures sharing one interface.
  *
  * The first case is the default; the Verilog define `targets$<I>$<case>` selects another, `<I>` being the interface
  * class name.
  */
object InstanceChoice:
  inline def apply[L <: LayerInterface[?], I <: HWInterface[?], P <: DVInterface[?, ?]](
    inline cases:    Seq[
      (String, (Arena, Context, MlirBlock, Interface[I], ProbeInterface[P], L, InstanceContext) ?=> Unit)
    ]
  )(
    using arena:     Arena,
    context:         Context,
    block:           MlirBlock,
    file:            sourcecode.File,
    line:            sourcecode.Line,
    name:            sourcecode.Name.Machine,
    instanceContext: InstanceContext
  ): Instance[I, P] =
    ${ instanceChoiceImpl[L, I, P]('cases, 'arena, 'context, 'block, 'file, 'line, 'name, 'instanceContext) }

def instanceChoiceImpl[L <: LayerInterface[?]: Type, I <: HWInterface[?]: Type, P <: DVInterface[?, ?]: Type](
  cases:           Expr[Seq[(String, (Arena, Context, MlirBlock, Interface[I], ProbeInterface[P], L, InstanceContext) ?=> Unit)]],
  arena:           Expr[Arena],
  context:         Expr[Context],
  block:           Expr[MlirBlock],
  file:            Expr[sourcecode.File],
  line:            Expr[sourcecode.Line],
  name:            Expr[sourcecode.Name.Machine],
  instanceContext: Expr[InstanceContext]
)(
  using Quotes
): Expr[Instance[I, P]] =
  import quotes.reflect.*
  type Architecture = (Arena, Context, MlirBlock, Interface[I], ProbeInterface[P], L, InstanceContext) ?=> Unit
  val api                = Expr.summon[GeneratorApi].getOrElse(report.errorAndAbort("No GeneratorApi found"))
  val generatorType      = TypeRepr.of[Generator[?, ?, ?, ?]].typeSymbol
  val architectureMethod = generatorType.declaredMethod("architecture").head

  def dependsOnContext(tree: Tree, parameters: Set[Symbol]): Boolean =
    val references = new TreeAccumulator[Boolean]:
      def foldTree(found: Boolean, tree: Tree)(owner: Symbol): Boolean =
        found || parameters.contains(tree.symbol) || foldOverTree(false, tree)(owner)
    references.foldTree(false, tree)(Symbol.spliceOwner)

  def forwarded(term: Term): Symbol = term match
    case Inlined(_, Nil, expression) => forwarded(expression)
    case Typed(expression, _)        => forwarded(expression)
    case reference: Ident => reference.symbol
    case _ => Symbol.noSymbol

  def directCall(term: Term, contextual: List[Symbol] = Nil): (Term, Term) = term match
    case Inlined(_, Nil, expression)                                                       => directCall(expression, contextual)
    case Typed(expression, _)                                                              => directCall(expression, contextual)
    case Block(Nil, expression)                                                            => directCall(expression, contextual)
    case Lambda(parameters, body)                                                          => directCall(body, contextual ++ parameters.map(_.symbol))
    case Apply(Select(body, "apply"), arguments) if body.tpe <:< TypeRepr.of[Architecture] =>
      if arguments.map(forwarded) != contextual then
        report.errorAndAbort("InstanceChoice cases cannot override contextual arguments", term.pos)
      directCall(body, contextual)
    case Apply(method @ Select(receiver, "architecture"), List(parameter))
        if receiver.tpe.widen.baseClasses.contains(generatorType) &&
          (method.symbol == architectureMethod || architectureMethod.overridingSymbol(
            method.symbol.owner
          ) == method.symbol) =>
      if dependsOnContext(receiver, contextual.toSet) || dependsOnContext(parameter, contextual.toSet) then
        report.errorAndAbort(
          "InstanceChoice generator and parameter cannot depend on the case's contextual arguments",
          term.pos
        )
      (receiver, parameter)
    case _                                                                                 =>
      report.errorAndAbort("InstanceChoice cases must call generator.architecture(parameter) directly", term.pos)

  val entries = cases match
    case '{ Seq[(String, Architecture)](${ Varargs(entries) }*) } => entries
    case _                                                        => report.errorAndAbort("InstanceChoice needs an inline Seq of architecture calls", cases)
  val targets = entries.map: entry =>
    val (label, architecture) = entry match
      case '{ ($label: String).->[Architecture]($architecture) } => (label, architecture)
      case _                                                     => report.errorAndAbort("InstanceChoice cases must be name -> generator.architecture(parameter)", entry)
    val (receiver, parameter) = directCall(architecture.asTerm)
    val generator =
      Symbol.newVal(Symbol.spliceOwner, "generator", receiver.tpe.widen, Flags.EmptyFlags, Symbol.noSymbol)
    val argument =
      Symbol.newVal(Symbol.spliceOwner, "parameter", parameter.tpe.widen, Flags.EmptyFlags, Symbol.noSymbol)
    val caseName = Symbol.newVal(Symbol.spliceOwner, "caseName", TypeRepr.of[String], Flags.EmptyFlags, Symbol.noSymbol)
    val typeArguments = receiver.tpe.widen.baseType(generatorType).typeArgs
    def call(method: String): Term = Select.unique(Ref(generator), method).appliedTo(Ref(argument))
    val dump = Select
      .unique(api.asTerm, "dumpMlirbc")
      .appliedToTypes(typeArguments)
      .appliedTo(Ref(generator))
      .appliedTo(Ref(argument))
      .appliedToArgs(List(arena.asTerm, context.asTerm))
    val target = '{
      (
        ${ Ref(caseName).asExprOf[String] },
        ${ call("moduleName").asExprOf[String] },
        ${ call("interface").asExprOf[I] },
        ${ call("probe").asExprOf[P] },
        ${ call("layers").asExprOf[L] }
      )
    }
    Block(
      List(
        ValDef(caseName, Some(label.asTerm.changeOwner(caseName))),
        ValDef(generator, Some(receiver.changeOwner(generator))),
        ValDef(argument, Some(parameter.changeOwner(argument))),
        dump
      ),
      target.asTerm
    ).asExprOf[(String, String, I, P, L)]
  '{
    BaseGeneratorHelper.instantiateChoice[L, I, P](
      ${ Expr(TypeRepr.of[I].typeSymbol.name) },
      ${ Expr.ofSeq(targets) }
    )(
      using $arena,
      $context,
      $block,
      $file,
      $line,
      $name,
      $instanceContext
    )
  }
