// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.magic.macros

import scala.language.reflectiveCalls
import scala.quoted.*
import scala.reflect.Selectable.reflectiveSelectable

private def summonContextualParameters(
  using Quotes
) =
  import quotes.reflect.*
  val arena           = Expr.summon[java.lang.foreign.Arena].getOrElse {
    report.errorAndAbort("No implicit value found for Arena.")
  }
  val typeImpl        = Expr.summon[me.jiuyang.zaozi.TypeImpl].getOrElse {
    report.errorAndAbort("No implicit value found for TypeImpl.")
  }
  val context         = Expr.summon[org.llvm.mlir.scalalib.capi.ir.Context].getOrElse {
    report.errorAndAbort("No implicit value found for Context.")
  }
  val block           = Expr.summon[org.llvm.mlir.scalalib.capi.ir.Block].getOrElse {
    report.errorAndAbort("No implicit value found for Block.")
  }
  val file            = Expr.summon[sourcecode.File].getOrElse {
    report.errorAndAbort("No implicit value found for sourcecode.File.")
  }
  val line            = Expr.summon[sourcecode.Line].getOrElse {
    report.errorAndAbort("No implicit value found for sourcecode.Line.")
  }
  val valName         = Expr.summon[sourcecode.Name.Machine].getOrElse {
    report.errorAndAbort("No implicit value found for sourcecode.Name.Machine.")
  }
  val instanceContext =
    Expr.summon[me.jiuyang.zaozi.InstanceContext].getOrElse {
      report.errorAndAbort("No implicit value found for me.jiuyang.zaozi.InstanceContext.")
    }

  (arena, typeImpl, context, block, file, line, valName, instanceContext)

/** This macro takes [[fieldName]] from dynamic access, retrieve type at compile time and call runtimeSelectDynamic to
  * do subaccess
  *
  * TODO: think about:
  * {{{
  *   trait RefElementViaValName[D <: Data, R <: Referable[D]]:
  *     extension (ref: R)
  *     def refViaValName[E <: Data](
  *       fieldName: String,
  *       ctx:       Context,
  *       file:      sourcecode.File,
  *       line:      sourcecode.Line,
  *       valName:   sourcecode.Name.Machine,
  *       instCtx:   InstanceContext
  *     ): Ref[E]
  *   given [D <: Bundle, R <: Referable[D]]: RefElementViaValName[D, R]
  * }}}
  * macro here can use Implicits.search to find the implicit instance from given. another issue is I don't have a good
  * idea to deal with valName on BundleField.
  */
def referableSelectDynamic[T <: me.jiuyang.zaozi.valuetpe.Data: Type](
  ref:       Expr[me.jiuyang.zaozi.reftpe.Referable[T]],
  fieldName: Expr[String]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*

  // Get the type of `tpe` field from `Referable`
  val referableType       = TypeRepr.of[T]
  val dynamicSubfieldType = TypeRepr.of[me.jiuyang.zaozi.magic.DynamicSubfield]

  // Ensure T is a subtype of Bundle
  if (!(referableType <:< dynamicSubfieldType)) {
    report.errorAndAbort(s"Type parameter T must be a subtype of DynamicSubfield, but got ${referableType.show}.")
  }

  // Check if the field exists in the Bundle type
  val fieldNameStr   = fieldName.valueOrAbort
  val fieldSymbolOpt = referableType.classSymbol.flatMap(_.declaredFields.find(_.name == fieldNameStr))
  val fieldSymbol    = fieldSymbolOpt.getOrElse {
    report.errorAndAbort(s"Field '$fieldNameStr' does not exist in type ${referableType.show}.")
  }

  val fieldType = fieldSymbol.tree match {
    case ValDef(_, fieldTypeTree, _) =>
      // Substitute type parameters in the field type with the concrete type arguments from referableType
      val typeParams = referableType.typeSymbol.declaredTypes.filter(_.isTypeParam)
      val typeArgs   = referableType.typeArgs
      fieldTypeTree.tpe.substituteTypes(typeParams.take(typeArgs.length), typeArgs)
    case _                           => report.errorAndAbort(s"Unable to determine the type of field '$fieldNameStr'.")
  }

  val (arena, typeImpl, context, block, file, line, valName, _) = summonContextualParameters

  // Ensure the field type conforms to Data
  val bundleFieldType       = TypeRepr.of[me.jiuyang.zaozi.valuetpe.BundleField[?]]
  val optionBundleFieldType = TypeRepr.of[Option[me.jiuyang.zaozi.valuetpe.BundleField[?]]]
  if (fieldType <:< bundleFieldType)
    fieldType.typeArgs.head.asType match
      case '[tpe] =>
        '{
          given java.lang.foreign.Arena                = $arena
          given me.jiuyang.zaozi.TypeImpl              = $typeImpl
          given org.llvm.mlir.scalalib.capi.ir.Context = $context
          given org.llvm.mlir.scalalib.capi.ir.Block   = $block
          given sourcecode.File                        = $file
          given sourcecode.Line                        = $line
          given sourcecode.Name.Machine                = $valName
          $ref._tpe
            .asInstanceOf[me.jiuyang.zaozi.magic.DynamicSubfield]
            // Hack with union type
            .getRefViaFieldValName[tpe & me.jiuyang.zaozi.valuetpe.Data](
              $ref.refer,
              $fieldName
            )
        }
  else if (fieldType <:< optionBundleFieldType)
    fieldType.typeArgs.head.typeArgs.head.asType match
      case '[tpe] =>
        '{
          given java.lang.foreign.Arena                = $arena
          given me.jiuyang.zaozi.TypeImpl              = $typeImpl
          given org.llvm.mlir.scalalib.capi.ir.Context = $context
          given org.llvm.mlir.scalalib.capi.ir.Block   = $block
          given sourcecode.File                        = $file
          given sourcecode.Line                        = $line
          given sourcecode.Name.Machine                = $valName
          $ref._tpe
            .asInstanceOf[me.jiuyang.zaozi.magic.DynamicSubfield]
            // Hack with union type
            .getOptionRefViaFieldValName[tpe & me.jiuyang.zaozi.valuetpe.Data](
              $ref.refer,
              $fieldName
            )
        }
  else
    report.errorAndAbort(s"Field type '${fieldType.show}' does not conform to the upper bound BundleField.")

private def getTypeParameters(
  fieldValueExpr: Expr[Any]
)(
  using Quotes
) =
  import quotes.reflect.*

  val rType                = fieldValueExpr.asTerm.tpe
  if (!(rType <:< TypeRepr.of[me.jiuyang.zaozi.reftpe.Referable[?]]))
    report.errorAndAbort(s"Type parameter R must be a subtype of Referable, but got ${rType.show}.")
  val (vTypeOpt, eTypeOpt) = rType.match
    case AppliedType(_, List(vecAndData)) =>
      vecAndData match
        case AndType(vecOrBits, _) =>
          vecOrBits match
            case AppliedType(_, List(data)) =>
              if (!(vecOrBits <:< TypeRepr.of[me.jiuyang.zaozi.valuetpe.Vec[?]]))
                report.errorAndAbort(s"Type parameter V must be a subtype of Vec, but got ${vecOrBits.show}.")
              if (!(data <:< TypeRepr.of[me.jiuyang.zaozi.valuetpe.Data]))
                report.errorAndAbort(s"Type parameter E must be a subtype of Data, but got ${data.show}.")
              (Some(vecOrBits), Some(data))
            case bits                       =>
              if (!(bits =:= TypeRepr.of[me.jiuyang.zaozi.valuetpe.Bits]))
                report.errorAndAbort(s"Element type must be Bits, but got ${bits.show}.")
              (None, None)

  (rType, vTypeOpt, eTypeOpt)

def referableApplyCall[T <: me.jiuyang.zaozi.valuetpe.Data: Type](
  using Quotes
)(ref:       Expr[me.jiuyang.zaozi.reftpe.Referable[T]],
  fieldName: Expr[String],
  args:      Seq[quotes.reflect.Term]
): Expr[Any] =
  import quotes.reflect.*

  // Get the "field" expression via selectDynamic
  val fieldValueExpr = referableSelectDynamic[T](ref, fieldName)

  val contextualArgs = summonContextualParameters match
    case (arena, _, context, block, file, line, valName, instanceContext) =>
      List(arena, context, block, file, line, valName, instanceContext).map(_.asTerm)
  val fieldValueTerm = fieldValueExpr.asTerm

  // If we have more than one apply() method, we can dispatch them here based on the args type.
  val applyCallTerm = getTypeParameters(fieldValueExpr) match
    case (rType, Some(vType), Some(eType)) =>
      args match
        case idx +: Nil =>
          Select
            .unique(
              Ref(Symbol.requiredModule("me.jiuyang.zaozi.default.given_VecApi_E_V_R"))
                .appliedToTypes(List(eType, vType, rType)),
              "ref"
            )
            .appliedTo(fieldValueTerm)
            .appliedTo(idx)
            .appliedToArgs(contextualArgs)
        case _          => report.errorAndAbort(s"Expected 1 args, but got ${args.length}")
    case (rType, None, None)               =>
      args match
        case idx +: Nil      =>
          Select
            .unique(
              Ref(Symbol.requiredModule("me.jiuyang.zaozi.default.given_BitsApi_LHS_RHS"))
                .appliedToTypes(List(rType, rType)),
              // apply() is just the syntax sugar of bit/bits...
              "bit"
            )
            .appliedTo(fieldValueTerm)
            .appliedTo(idx)
            .appliedToArgs(contextualArgs)
        case hi +: lo +: Nil =>
          Select
            .unique(
              Ref(Symbol.requiredModule("me.jiuyang.zaozi.default.given_BitsApi_LHS_RHS"))
                .appliedToTypes(List(rType, rType)),
              "bits"
            )
            .appliedTo(fieldValueTerm)
            .appliedToArgs(List(hi, lo))
            .appliedToArgs(contextualArgs)
        case _               => report.errorAndAbort(s"Expected 1 or 2 args, but got ${args.length}")
    case (rType, vTypeOpt, eTypeOpt)       =>
      report.errorAndAbort(
        s"Unexpected type parameters ${rType.show}, ${vTypeOpt.flatMap(t => Some(t.show))} and ${eTypeOpt
            .flatMap(t => Some(t.show))}"
      )

  // Turn it back into an Expr
  applyCallTerm.asExpr

def referableApplyDynamic[T <: me.jiuyang.zaozi.valuetpe.Data: Type](
  ref:       Expr[me.jiuyang.zaozi.reftpe.Referable[T]],
  fieldName: Expr[String],
  args:      Expr[Seq[Any]]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  val varargs = args match
    case Varargs(exprs) => exprs.map(_.asTerm)
    case other          =>
      report.errorAndAbort(s"Expected varargs for applyDynamic, got: ${other.show}")

  referableApplyCall(ref, fieldName, varargs)

def referableApplyDynamicNamed[T <: me.jiuyang.zaozi.valuetpe.Data: Type](
  ref:       Expr[me.jiuyang.zaozi.reftpe.Referable[T]],
  fieldName: Expr[String],
  args:      Expr[Seq[(String, Any)]]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*
  val varargs = args match
    case Varargs(argExprs) =>
      (argExprs.map {
        case '{ ($key: String, $value: Any) } =>
          key.value match
            case Some(k) => NamedArg(k, value.asTerm)
            case None    =>
              report.errorAndAbort("Named argument must have a statically known key string.")
        case other                            =>
          report.errorAndAbort(s"Expected a literal (String, Any), got: ${other.show}")
      } match
        // we will drop the names of the arguments later so check them here
        case idx +: Nil          =>
          if (idx.name != "idx") report.errorAndAbort(s"Unexpected named arguments ${idx.name}.")
          Seq(idx)
        case arg1 +: arg2 +: Nil =>
          (arg1.name, arg2.name) match
            case ("hi", "lo")   => Seq(arg1, arg2)
            case ("lo", "hi")   => Seq(arg2, arg1)
            case (name1, name2) => report.errorAndAbort(s"Unexpected named arguments (${name1}, ${name2}).")
        case args                => args
      ).map(_.value)
    case other             =>
      report.errorAndAbort(s"Expected varargs for applyDynamicNamed, got: ${other.show}")

  referableApplyCall(ref, fieldName, varargs)
