// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** A two's-complement signed integer of [[width]] bits (FIRRTL's `!firrtl.sint<width>`). Obtained from a
  * [[Bits]]/[[UInt]] via `asSInt`, or as a signed literal via `BigInt#S`.
  */
trait SInt extends Element with CanProbe:
  private[zaozi] val _width: Int

  def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
