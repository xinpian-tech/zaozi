// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** A single-bit boolean (FIRRTL's `!firrtl.uint<1>` used as a logical value), with `&`, `|`, `^`, `!`, and the `?` mux
  * operator.
  */
trait Bool extends Element with CanProbe:
  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
