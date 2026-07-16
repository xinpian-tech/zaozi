// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

import me.jiuyang.zaozi.TypeImpl
import org.llvm.mlir.scalalib.capi.ir.{Context, Type}

import java.lang.foreign.Arena

/** A reset signal (FIRRTL's abstract `!firrtl.reset`, resolved to sync/async by the [[me.jiuyang.zaozi.ResetScope]] a
  * register is declared under). Used with [[me.jiuyang.zaozi.ResetScope]] to give registers an implicit reset.
  */
trait Reset extends Element with CanProbe:
  final def toMlirType(
    using Arena,
    Context,
    TypeImpl
  ): Type = this.toMlirTypeImpl
