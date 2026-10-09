// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Zaozi contributors
package me.jiuyang.zaozi.reftpe

import me.jiuyang.zaozi.valuetpe.{CanProbe, Data}
import org.llvm.mlir.scalalib.capi.ir.Value

trait ForceableReferable[T <: Data & CanProbe] extends Referable[T]:
  private[zaozi] val _forceableRefer: Value

/** A write-capable reference to a forceable declaration. */
abstract class ProbeWrite[T <: Data & CanProbe]:
  private[zaozi] val _tpe:   T
  private[zaozi] val _refer: Value
