// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.magic

import scala.quoted.*

/** SV declarations are singleton objects; only the FIRRTL adapter is constructed from concrete bindings. */
private[zaozi] def svGeneratorTransform(
  using Quotes
)(definition: quotes.reflect.Definition
): List[quotes.reflect.Definition] =
  import quotes.reflect.*
  val cls    = definition.asInstanceOf[ClassDef]
  val owner  = definition.symbol
  val base   = Symbol.requiredClass("me.jiuyang.zaozi.sv.SVGenerator")
  val firrtl = TypeTree.ref(owner.declaredType("FIRRTLIO").head)
  def constructor(name: String, result: TypeTree): DefDef =
    val inherited = base.methodMember(name).head
    if cls.body.exists(d => d.symbol.name == name) then report.errorAndAbort(s"Overriding $name is forbidden")
    val method    = Symbol.newMethod(owner, name, owner.typeRef.memberType(inherited))
    DefDef(
      method,
      argss =>
        val args           = argss.map(_.map(_.asInstanceOf[Term]))
        val init           = Select.unique(New(result), "<init>")
        val supplied       = args.flatten
        val constructor    = result.tpe.typeSymbol.primaryConstructor
        val parameterLists = constructor.paramSymss.filterNot(_.exists(_.isType))
        val application    = parameterLists.foldLeft(init: Term): (call, list) =>
          call.appliedToArgs(list.map: parameter =>
            val expected = result.tpe.memberType(parameter)
            supplied
              .find(_.tpe <:< expected)
              .getOrElse(
                report.errorAndAbort(
                  s"Cannot supply constructor parameter ${parameter.name}: ${expected.show} for ${result.tpe.show}"
                )
              ))
        Some(application)
    )
  val mainSymbol = Symbol.newMethod(owner, "main", owner.typeRef.memberType(base.methodMember("main").head))
  val main    = DefDef(
    mainSymbol,
    argss =>
      Some(
        Select
          .unique(
            Expr
              .summon[me.jiuyang.zaozi.sv.SVGeneratorApi]
              .getOrElse(report.errorAndAbort("No given SVGeneratorApi was found"))
              .asTerm,
            "mainImpl"
          )
          .appliedToType(owner.typeRef)
          .appliedTo(This(owner))
          .appliedTo(argss.head.head.asInstanceOf[Term])
      )
  )
  val methods =
    List(constructor("interface", firrtl))
  val entry   = if cls.body.exists(_.symbol.name == "main") then Nil else List(main)
  List(ClassDef.copy(cls)(cls.name, cls.constructor, cls.parents, cls.self, methods ++ entry ++ cls.body))
