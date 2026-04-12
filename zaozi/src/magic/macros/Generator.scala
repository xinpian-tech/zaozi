// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package me.jiuyang.zaozi.magic.macros

import scala.annotation.{experimental, MacroAnnotation}
import scala.quoted.*
import scala.util.chaining.*

@experimental
class generator extends MacroAnnotation:
  def transform(
    using Quotes
  )(definition: quotes.reflect.Definition,
    companion:  Option[quotes.reflect.Definition]
  ): List[quotes.reflect.Definition] =
    import quotes.reflect.*
    definition match
      case ClassDef(name, constr, parents, selfOpt, body)
          // a bit of dirty but quotes does not expose the SingletonTypeTree
          if selfOpt.exists(_.tpt.toString().startsWith("SingletonTypeTree")) =>
        val objSym                                = definition.symbol
        val (tptParam, tptL, tptI, tptP, tptVOpt) = parents
          .map(parent =>
            parent match
              case Applied(
                    TypeIdent("Generator"),
                    List(param: TypeTree, l: TypeTree, i: TypeTree, p: TypeTree)
                  ) =>
                Some(param, l, i, p, None)
              case Applied(
                    TypeIdent("VerilogWrapper"),
                    List(param: TypeTree, l: TypeTree, i: TypeTree, p: TypeTree, v: TypeTree)
                  ) =>
                Some(param, l, i, p, Some(v))
              case _ => None
          )
          .flatten
          .headOption
          .getOrElse {
            report.errorAndAbort("@generator object should extends trait Generator or VerilogWrapper")
          }

        def makeInterfaceDef(symbolName: String, resultType: TypeTree) = DefDef(
          Symbol.newMethod(
            objSym,
            symbolName,
            // we have to construct MethodType manually since the type is different with the declaration's in Generator
            MethodType(List("parameter"))(methodType => List(tptParam.tpe), methodType => resultType.tpe)
          ),
          (argss: List[List[Tree]]) =>
            Some(Select.unique(New(resultType), "<init>").appliedTo(argss.head.head.asExpr.asTerm))
        )

        def parseParameterDef =
          val tpsParam     = tptParam.symbol
          val paramCompObj = tpsParam.companionClass
          if (
            !(tptParam.tpe <:< TypeRepr.of[java.io.Serializable]
              && paramCompObj.typeRef <:< TypeRepr.of[java.lang.Object])
          )
            report.errorAndAbort(s"${tptParam.show} should be a case class")

          Seq(
            selfOpt.get.tpt,
            tptParam,
            tpsParam.companionModule.tree.asInstanceOf[ValDef].tpt /* type of module class */
          )
            .map(_.tpe.asType) match
            case Seq('[tObj], '[tParam], '[tParamCompModule]) =>
              def parseParameterImpl(
                args:        Expr[Seq[String]]
              )(
                using owner: Quotes
              ) =
                // Ref: https://github.com/com-lihaoyi/mainargs/blob/e815520df9762111643208d57898441d87105811/mainargs/src-3/Macros.scala#L41
                val paramConstructor =
                  paramCompObj
                    .memberMethod("apply")
                    .find(
                      _.paramSymss.flatten.map(_.name) == tpsParam.primaryConstructor.paramSymss.flatten.map(_.name)
                    )
                    .getOrElse {
                      report.errorAndAbort(
                        s"Cannot find apply method in companion object of ${tptParam.tpe.show}",
                        paramCompObj.pos.getOrElse(Position.ofMacroExpansion)
                      )
                    }
                if (paramConstructor.paramSymss.length > 1)
                  report.errorAndAbort("Multiple parameter lists not supported")

                val argSigs = paramConstructor.paramSymss.flatten
                  .zip(LazyList.from(1))
                  .map: (param, idx) =>
                    param.tree.asInstanceOf[ValDef].tpt.tpe.asType match
                      case '[t] =>
                        val tokensReader = Expr
                          .summon[mainargs.TokensReader[t]]
                          .getOrElse:
                            report.errorAndAbort(
                              s"No TokensReader[${Type.show[t]}] found for parameter ${param.name}"
                            )

                        val defaultMethodPattern = """\$lessinit\$greater\$default\$(\d+)""".r
                        val defaultOpt           = paramCompObj.tree
                          .asInstanceOf[ClassDef]
                          .body
                          .collectFirst:
                            case d @ DefDef(defaultMethodPattern(nameIdx), _, _, _)
                                if param.flags.is(Flags.HasDefault) && nameIdx.toInt == idx =>
                              d
                        match
                          case Some(defaultMethod) =>
                            '{
                              Some((owner: tParamCompModule) =>
                                ${
                                  Select('{ owner }.asTerm, defaultMethod.symbol).asExpr match
                                    case '{ $value: `t` } => value
                                    case expr             => expr.asExprOf[t]
                                }
                              )
                            }
                          case None                => '{ None }

                        '{
                          mainargs.ArgSig
                            .create[t, tParamCompModule](
                              ${ Expr(param.name) },
                              new mainargs.arg,
                              ${ defaultOpt }
                            )(
                              using ${ tokensReader }
                            )
                        }
                  .pipe(Expr.ofList)

                val invokeRaw =
                  def callOf(
                    methodOwner: Expr[Any],
                    args:        Expr[Seq[Any]]
                  )(
                    using Quotes
                  ) =
                    val params = paramConstructor.paramSymss.head
                    methodOwner.asTerm
                      .select(paramConstructor)
                      .appliedToArgs(params.map: param =>
                        methodOwner.asTerm.tpe.memberType(paramConstructor).memberType(param).asType match
                          case '[t] => '{ $args(${ Expr(params.indexOf(param)) }).asInstanceOf[t] }.asTerm)
                      .asExprOf[tParam]

                  '{ (b: tParamCompModule, params: Seq[Any]) => ${ callOf('b, 'params) } }

                val mainData = '{
                  mainargs.MainData
                    .create[tParam, tParamCompModule](
                      "apply",
                      new mainargs.main,
                      ${ argSigs },
                      ${ invokeRaw }
                    )
                }

                '{
                  (new mainargs.ParserForClass[tParam](
                    $mainData.asInstanceOf[mainargs.MainData[tParam, Any]],
                    () =>
                      ${
                        Ident(tptParam.tpe match
                          case TypeRef(a, b) => TermRef(a, b)).asExpr
                      }
                  )).constructOrExit(${ args })
                }

              val parseParameterSymbol = Symbol.newMethod(
                objSym,
                "parseParameter",
                MethodType(List("args"))(
                  methodType => List(TypeRepr.of[Seq[String]]),
                  methodType => TypeRepr.of[tParam]
                )
              )
              DefDef(
                parseParameterSymbol,
                (argss: List[List[Tree]]) =>
                  Some(
                    parseParameterImpl(argss.head.head.asExprOf[Seq[String]])(
                      // pass method symbol as owner
                      using parseParameterSymbol.asQuotes
                    ).asTerm
                  )
              )

        def mainDef = DefDef(
          Symbol.newMethod(
            objSym,
            "main",
            Symbol.requiredClass("me.jiuyang.zaozi.Generator").declaredMethod("main").head.info
          ),
          (argss: List[List[Tree]]) =>
            tptParam.tpe.asType match
              case '[tParam] =>
                Some(
                  Select
                    .unique(
                      Expr
                        .summon[me.jiuyang.zaozi.GeneratorApi]
                        .getOrElse:
                          report.errorAndAbort("No given instance of me.jiuyang.zaozi.GeneratorApi was found")
                        .asTerm,
                      "mainImpl"
                    )
                    .appliedToTypeTrees(List(tptParam, tptL, tptI, tptP))
                    .appliedTo(This(objSym))
                    .appliedTo(argss.head.head.asExpr.asTerm)
                    .appliedTo(
                      Expr
                        .summon[upickle.default.ReadWriter[tParam]]
                        .getOrElse:
                          report.errorAndAbort(
                            s"No given instance of upickle.default.ReadWriter[${tptParam.show}] was Found"
                          )
                        .asTerm
                    )
                )
        )

        def defOpt[D <: Definition](definition: D)  =
          Option.unless(definition.symbol.overridingSymbol(objSym).exists)(definition)
        def defSome[D <: Definition](definition: D) =
          if (definition.symbol.overridingSymbol(objSym).exists)
            report.errorAndAbort(s"Overriding ${definition.symbol.name} is forbidden")
          Some(definition)

        val layersDefOpt    = makeInterfaceDef("layers", tptL).pipe(defSome)
        val interfaceDefOpt = makeInterfaceDef("interface", tptI).pipe(defSome)
        val probeDefOpt     = makeInterfaceDef("probe", tptP).pipe(defSome)

        val parseParameterDefOpt = parseParameterDef.pipe(defOpt)
        val mainDefOpt           = mainDef.pipe(defOpt)

        val newBody = List(
          layersDefOpt,
          interfaceDefOpt,
          probeDefOpt,
          parseParameterDefOpt,
          mainDefOpt
        ).flatten ++ body
        List(ClassDef.copy(definition)(name, constr, parents, selfOpt, newBody))
      case _ =>
        report.error("@generator should only annotate an object")
        List(definition)

def summonLayersImpl(
  using Quotes
): Expr[me.jiuyang.zaozi.LayerInterface[?]] =
  import quotes.reflect.*
  def findOwner(owner: Symbol, cond: Symbol => Boolean): Symbol =
    if (cond(owner)) owner else findOwner(owner.owner, cond)

  val invoker       = findOwner(Symbol.spliceOwner, _.isClassDef)
  val layerIntfType = invoker.typeRef.baseType(invoker.typeRef.baseClasses.find(_.name == "DVInterface").getOrElse {
    report.errorAndAbort("summonLayers should only be called in a DVInterface definition")
  }) match
    case AppliedType(_, List(_, t)) => t

  Select
    .unique(New(TypeTree.ref(layerIntfType.typeSymbol)), "<init>")
    .appliedTo(Select.unique(This(invoker), "parameter"))
    .asExprOf[me.jiuyang.zaozi.LayerInterface[?]]
