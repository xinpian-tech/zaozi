// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.tblib.macros

import me.jiuyang.tblib.{TestbenchIO, TestbenchPort}
import me.jiuyang.zaozi.HWInterface
import me.jiuyang.zaozi.valuetpe.{BundleField, Data}

import scala.quoted.*

private enum FieldShape:
  case Required(element: Any)
  case Optional(element: Any)

private def resolveFieldShape[I: Type](
  fieldName: Expr[String]
)(
  using Quotes
): FieldShape =
  import quotes.reflect.*

  val interfaceType = TypeRepr.of[I]
  val name          = fieldName.valueOrAbort
  val field         = interfaceType.classSymbol
    .flatMap(_.declaredFields.find(_.name == name))
    .getOrElse(report.errorAndAbort(s"Field '$name' does not exist in type ${interfaceType.show}."))
  val fieldType     = field.tree match
    case ValDef(_, typeTree, _) =>
      val parameters = interfaceType.typeSymbol.declaredTypes.filter(_.isTypeParam)
      typeTree.tpe.substituteTypes(parameters.take(interfaceType.typeArgs.length), interfaceType.typeArgs)
    case _                      => report.errorAndAbort(s"Unable to determine the type of field '$name'.")

  val bundleField         = TypeRepr.of[BundleField[?]]
  val optionalBundleField = TypeRepr.of[Option[BundleField[?]]]
  if fieldType <:< bundleField then FieldShape.Required(fieldType.typeArgs.head.asType)
  else if fieldType <:< optionalBundleField then FieldShape.Optional(fieldType.typeArgs.head.typeArgs.head.asType)
  else report.errorAndAbort(s"Field '$name' is not a hardware IO field.")

def testbenchIOSelectDynamic[I <: HWInterface[?]: Type](
  io:        Expr[TestbenchIO[I]],
  fieldName: Expr[String]
)(
  using Quotes
): Expr[Any] =
  resolveFieldShape[I](fieldName) match
    case FieldShape.Required(element) =>
      element.asInstanceOf[Type[?]] match
        case '[tpe] =>
          '{ $io.port[tpe & Data]($fieldName).asInstanceOf[TestbenchPort[tpe & Data]] }
    case FieldShape.Optional(element) =>
      element.asInstanceOf[Type[?]] match
        case '[tpe] =>
          '{ $io.portOption[tpe & Data]($fieldName).asInstanceOf[Option[TestbenchPort[tpe & Data]]] }
