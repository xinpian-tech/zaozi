// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** An unsigned integer of [[width]] bits (FIRRTL's `!firrtl.uint<width>`), with arithmetic (`+`, `-`, `*`, ...),
  * comparison, and shift operators. Widths grow according to FIRRTL rules (e.g. `+`/`-` produce one more bit than the
  * wider operand) rather than wrapping or saturating; truncate explicitly (e.g. `.asBits.tail(1)`) where wraparound is
  * wanted.
  */
trait UInt extends Element with CanProbe:
  private[zaozi] val _width: Int

  final def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
