// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** A bidirectional analog wire of [[width]] bits (FIRRTL's `!firrtl.analog<width>`), used for things like tristate
  * buses. Unlike other [[Element]]s it does not extend [[CanProbe]].
  */
trait Analog extends Element:
  private[zaozi] val _width: Int

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
