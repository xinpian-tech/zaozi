// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.magic

import scala.quoted.*

def constraintInterfaceSelectDynamic[I <: me.jiuyang.zaozi.HWInterface[?]: Type](
  interface: Expr[me.jiuyang.utlib.ConstraintInterface[I]],
  fieldName: Expr[String]
)(
  using Quotes
): Expr[Any] =
  import quotes.reflect.*

  val interfaceType = TypeRepr.of[I]
  val name          = fieldName.valueOrAbort
  val fieldSymbol   = interfaceType.classSymbol.flatMap(_.declaredFields.find(_.name == name)).getOrElse {
    report.errorAndAbort(s"Field '$name' does not exist in type ${interfaceType.show}.")
  }
  val fieldType     = fieldSymbol.tree match
    case ValDef(_, fieldTypeTree, _) =>
      val typeParameters = interfaceType.typeSymbol.declaredTypes.filter(_.isTypeParam)
      fieldTypeTree.tpe.substituteTypes(typeParameters.take(interfaceType.typeArgs.length), interfaceType.typeArgs)
    case _                           => report.errorAndAbort(s"Unable to determine the type of field '$name'.")

  val bundleFieldType = TypeRepr.of[me.jiuyang.zaozi.valuetpe.BundleField[?]]
  if !(fieldType <:< bundleFieldType) then
    report.errorAndAbort(s"Field '$name' has unsupported type ${fieldType.show}; expected BundleField.")

  '{ $interface.field($fieldName) }
