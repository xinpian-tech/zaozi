// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.utlib.magic

import scala.quoted.*

/** Compile-time check that `name` is a field of the DUT's IO before resolving a drive port — driving a port the DUT
  * does not have fails to compile.
  */
def abiDriveSelectDynamic[I <: me.jiuyang.zaozi.HWInterface[?]: Type](
  access:    Expr[me.jiuyang.utlib.DriveAccess[I]],
  fieldName: Expr[String]
)(
  using Quotes
): Expr[me.jiuyang.utlib.AbiPort] =
  checkField[I](fieldName, "drive port")
  '{ $access.field($fieldName) }

/** Compile-time check that `name` is a field of the DUT's Probe before resolving a probe port — observing a probe the
  * DUT does not expose fails to compile.
  */
def abiProbeSelectDynamic[P <: me.jiuyang.zaozi.DVInterface[?, ?]: Type](
  access:    Expr[me.jiuyang.utlib.ProbeAccess[P]],
  fieldName: Expr[String]
)(
  using Quotes
): Expr[me.jiuyang.utlib.AbiPort] =
  checkField[P](fieldName, "probe port")
  '{ $access.field($fieldName) }

private def checkField[T: Type](
  fieldName: Expr[String],
  what:      String
)(
  using Quotes
): Unit =
  import quotes.reflect.*
  val tpe  = TypeRepr.of[T]
  val name = fieldName.valueOrAbort
  tpe.classSymbol.flatMap(_.declaredFields.find(_.name == name)).getOrElse {
    report.errorAndAbort(s"$what '$name' is not a field of ${tpe.show}")
  }
